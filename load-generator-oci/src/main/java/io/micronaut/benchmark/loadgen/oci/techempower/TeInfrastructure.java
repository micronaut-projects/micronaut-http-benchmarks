package io.micronaut.benchmark.loadgen.oci.techempower;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.benchmark.loadgen.oci.AbstractInfrastructure;
import io.micronaut.benchmark.loadgen.oci.AsyncProfilerHelper;
import io.micronaut.benchmark.loadgen.oci.Compute;
import io.micronaut.benchmark.loadgen.oci.HotspotConfiguration;
import io.micronaut.benchmark.loadgen.oci.OciLocation;
import io.micronaut.benchmark.loadgen.oci.PhaseTracker;
import io.micronaut.benchmark.loadgen.oci.SshUtil;
import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.cmd.ProcessBuilder;
import io.micronaut.benchmark.loadgen.oci.cmd.ProcessHandle;
import io.micronaut.benchmark.loadgen.oci.resource.ResourceContext;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class TeInfrastructure extends AbstractInfrastructure {
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\x1B\\[[0-?]*[ -/]*[@-~]");
    private static final Logger LOG = LoggerFactory.getLogger(TeInfrastructure.class);
    private final Factory factory;

    private final Map<DockerServer, DockerServerRuntime> dockerServers = new EnumMap<>(DockerServer.class);

    private TeInfrastructure(Factory factory, OciLocation location, Path logDirectory) {
        super(factory.baseFactory, location, logDirectory);
        this.factory = factory;
    }

    public void start(PhaseTracker.PhaseUpdater phaseUpdater) throws Exception {
        setupBase(phaseUpdater);

        for (DockerServer dockerServer : DockerServer.values()) {
            dockerServers.put(dockerServer, new DockerServerRuntime(computeBuilder(dockerServer.instanceType)
                    .privateIp(dockerServer.ip)
                    .launch(), new OutputListener.Write(Files.newOutputStream(logDirectory.resolve(dockerServer.instanceType + ".log")))));
        }

        List<Future<?>> setupFutures = new ArrayList<>();
        for (DockerServer dockerServer : DockerServer.values()) {
            setupFutures.add(factory.executor.submit(() -> {
                DockerServerRuntime instance = dockerServers.get(dockerServer);
                instance.instance.awaitStartup();

                try (CommandRunner session = instance.instance.connectSsh()) {
                    // set up docker on the main runtime servers

                    SshUtil.run(session, "sudo dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo", instance.log);

                    SshUtil.run(session, "sudo mkdir -p /etc/systemd/system/docker.socket.d", instance.log);
                    // tcp listener on port 2375
                    SshUtil.run(session, "echo \"[Socket]\nListenStream=\nListenStream=0.0.0.0:2375\nSocketMode=\" | sudo tee /etc/systemd/system/docker.socket.d/override.conf", instance.log);
                    retry(() -> {
                        SshUtil.run(session, "sudo dnf install -y docker-ce git patch", instance.log);
                        return null;
                    });
                    SshUtil.run(session, "sudo systemctl start docker.socket", instance.log);
                }
                return null;
            }));
        }
        for (Future<?> setupFuture : setupFutures) {
            setupFuture.get();
        }
    }

    private static <T> List<T> shuffled(List<T> list) {
        List<T> shuffled = new ArrayList<>(list);
        Collections.shuffle(shuffled);
        return shuffled;
    }

    public Results run(Path resultDirectory, List<Revision> revisions) throws Exception {
        revisions = new ArrayList<>(revisions);
        Revision tefbRepoRevision = revisions.stream().filter(r -> r.modulePrefix() == null).findAny().orElseThrow();
        // move to start
        revisions.remove(tefbRepoRevision);
        revisions.addFirst(tefbRepoRevision);

        StringBuilder toolsetCommand = new StringBuilder("cd " + tefbRepoRevision.folderName() + " && DOCKER_HOST=tcp://127.0.0.1:2375 ./tfb");
        for (DockerServer s : DockerServer.values()) {
            if (s.toolsetArg != null) {
                toolsetCommand.append(" --").append(s.toolsetArg).append("-host ").append(s.ip);
            }
        }
        toolsetCommand.append(" --network-mode host");
        toolsetCommand.append(" --test");
        for (String test : shuffled(factory.configuration.tests())) {
            toolsetCommand.append(" ").append(test);
        }
        toolsetCommand.append(" --type");
        for (String type : shuffled(factory.configuration.types())) {
            toolsetCommand.append(" ").append(type);
        }
        toolsetCommand.append(" --results-environment '");
        for (DockerServer s : DockerServer.values()) {
            if (s != DockerServer.TOOLSET) {
                Compute.ComputeConfiguration.InstanceType type = factory.compute.getInstanceType(s.instanceType);
                // all loaded from config, so we don't need to worry about command injection
                toolsetCommand.append(s.toolsetArg).append(" (").append(type.shape()).append(", ").append(type.ocpus()).append(" cores, ").append(type.memoryInGb()).append("G)").append(' ');
            }
        }
        toolsetCommand.append("'");
        toolsetCommand.append(" --results-name '");
        for (Revision revision : revisions) {
            toolsetCommand.append(revision.githubRepoName()).append(":").append(revision.ref()).append(" ");
        }
        toolsetCommand.append("'");
        toolsetCommand.append(" --test-container-memory ")
                .append(factory.compute.getInstanceType(DockerServer.SERVER.instanceType).memoryInGb() - 1).append('g');
        toolsetCommand.append(" --duration ")
                .append(factory.configuration.duration().toSeconds());

        DockerServerRuntime server = dockerServers.get(DockerServer.SERVER);
        Map<String, AsyncProfilerHelper.Session> asyncProfilerSessions = null;
        if (factory.asyncProfilerConfiguration.enabled()) {
            LOG.info("Preparing async-profiler");
            asyncProfilerSessions = new HashMap<>();
            try (CommandRunner initSession = server.instance.connectSsh()) {
                // needed to build jfr config
                SshUtil.run(initSession, "sudo dnf install jdk-" + factory.hotspotConfiguration.version() + "-headful -y", server.log, 0, 1);
                for (String test : factory.configuration.tests()) {
                    Path dir = resultDirectory.resolve(test);
                    try {
                        Files.createDirectories(dir);
                    } catch (FileAlreadyExistsException ignored) {
                    }
                    OutputListener.Write log = new OutputListener.Write(Files.newOutputStream(dir.resolve("async-profiler.log")));
                    AsyncProfilerHelper.Session session = factory.asyncProfilerHelper.createSession(log);
                    session.initAgent(initSession);
                    asyncProfilerSessions.put(test, session);
                }
            }
        }

        DockerServerRuntime toolset = dockerServers.get(DockerServer.TOOLSET);
        try (CommandRunner session = toolset.instance.connectSsh()) {
            for (Revision revision : revisions) {
                LOG.info("Downloading {}", revision.githubRepoName());
                String dest = revision.equals(tefbRepoRevision) ? revision.folderName() : tefbRepoRevision.folderName() + "/frameworks/Java/micronaut/" + revision.folderName();
                SshUtil.run(
                        session,
                        "rm -rf tmp.zip " + dest + " && " +
                        "wget -O tmp.zip https://github.com/" + revision.githubRepoName() + "/archive/" + revision.ref() + ".zip && " +
                        "mkdir " + dest + " && " +
                        "cd " + dest + " && " +
                        "unzip ~/tmp.zip && " +
                        "mv */* .", // move files to the proper level
                        toolset.log);
            }

            if (asyncProfilerSessions != null) {
                LOG.info("Patching docker setup");
                ProcessBuilder builder = session.builder("cd " + tefbRepoRevision.folderName() + " && patch -p1")
                        .forwardOutput(toolset.log);
                builder.setIn(TeInfrastructure.class.getResourceAsStream("/tfb-async-profiler.diff"));
                try (ProcessHandle handle = builder.start()) {
                    handle.waitFor().check();
                }
            }

            boolean anyOtherProjects = revisions.stream().anyMatch(r -> r.modulePrefix() != null);
            if (anyOtherProjects) {
                LOG.info("Patching settings.gradle");
                String settingsGradlePath = tefbRepoRevision.folderName() + "/frameworks/Java/micronaut/settings.gradle";
                StringBuilder settingsGradle = new StringBuilder(new String(session.downloadBytes(settingsGradlePath), StandardCharsets.UTF_8));
                for (Revision revision : revisions) {
                    if (revision.modulePrefix() != null) {
                        settingsGradle.append("""
                            \s
                            includeBuild("%s") {
                                def modules = file("%s/settings.gradle").readLines().findAll {
                                       (it.startsWith('include "') || it.startsWith("include '")) && !it.contains('test') && !it.contains('bom') && !it.contains('benchmark')
                                    }.collect { it.substring(9, it.length() - 1) }
                                dependencySubstitution {
                                    modules.each { mod ->
                                            substitute module("%s${mod}") using project(":micronaut-$mod")
                                    }
                                }
                            }
                            """.formatted(revision.folderName(), revision.folderName(), revision.modulePrefix()));
                    }
                }
                Path settingsGradleLocal = resultDirectory.resolve("settings.gradle");
                Files.writeString(settingsGradleLocal, settingsGradle.toString());
                session.upload(settingsGradleLocal, settingsGradlePath);
            }

            if (asyncProfilerSessions != null || anyOtherProjects) {
                LOG.info("Patching dockerfiles");
                for (String key : factory.configuration.tests()) {
                    String dockerfileName = key + ".dockerfile";

                    String path = tefbRepoRevision.folderName() + "/frameworks/Java/micronaut/" + dockerfileName;
                    StringBuilder dockerfile = new StringBuilder(new String(session.downloadBytes(path), StandardCharsets.UTF_8));
                    if (asyncProfilerSessions != null) {
                        dockerfile.insert(dockerfile.lastIndexOf("\n") + 1, "ENV JAVA_OPTIONS=" + asyncProfilerSessions.get(key).getJvmArgument() + "\n");
                    }
                    if (anyOtherProjects) {
                        dockerfile.insert(dockerfile.indexOf("\n") + 1, key.contains("graal") ? "RUN microdnf install java-17-openjdk-headless\n" : "RUN apt update && apt install -y openjdk-17-jdk-headless\n");
                    }

                    Path localPath = resultDirectory.resolve(dockerfileName);
                    Files.writeString(localPath, dockerfile.toString());
                    session.upload(localPath, path);
                }
            }

            LOG.info("Running benchmark");
            SshUtil.run(session, toolsetCommand.toString(), toolset.log, new OutputListener.Log(LOG, Level.INFO) {
                @Override
                protected void log(String msg) {
                    msg = ANSI_ESCAPE.matcher(msg).replaceAll("");
                    if (msg.startsWith("Running Test: ") || msg.startsWith("VERIFYING ")) {
                        super.log(msg);
                    }
                }
            });

            LOG.info("Downloading results");
            session.downloadRecursive(tefbRepoRevision.folderName() + "/results", resultDirectory);
        }

        if (asyncProfilerSessions != null) {
            try (CommandRunner dlSession = server.instance.connectSsh()) {
                for (Map.Entry<String, AsyncProfilerHelper.Session> entry : asyncProfilerSessions.entrySet()) {
                    LOG.info("Downloading async-profiler data for {}", entry.getKey());
                    entry.getValue().finish(dlSession, resultDirectory.resolve(entry.getKey()));
                }
            }
        }

        LOG.info("Uploading results for sharing");
        Path ourResultDir;
        try (Stream<Path> list = Files.list(resultDirectory.resolve("results"))) {
            ourResultDir = list.max(Comparator.comparing(p -> {
                try {
                    return Files.getLastModifiedTime(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            })).orElseThrow();
        }
        byte[] resultBytes = Files.readAllBytes(ourResultDir.resolve("results.json"));
        Results results = factory.objectMapper.readValue(resultBytes, Results.class);
        // remove irrelevant metadata
        results.testMetadata().removeIf(tm -> !factory.configuration.tests().contains(tm.name()));
        LOG.info("Partial result URL: {}", uploadResults(factory.httpClient, results));
        return results;
    }

    static String uploadResults(HttpClient httpClient, Results results) throws JsonProcessingException {
        Map response = httpClient.toBlocking().retrieve(HttpRequest.POST("https://tfb-status.techempower.com/share/upload", new ObjectMapper().writeValueAsBytes(results)), Map.class);
        return response.get("visualizeResultsUrl").toString();
    }

    @Override
    public void close() throws Exception {
        for (DockerServerRuntime runtime : dockerServers.values()) {
            runtime.log.close();
            runtime.instance.terminateAsync();
        }

        terminateRelayAsync();

        for (DockerServerRuntime runtime : dockerServers.values()) {
            runtime.instance.close();
        }

        super.close();
    }

    @Singleton
    public record Factory(
            AbstractInfrastructure.Factory baseFactory,
            ResourceContext context,
            Compute compute,
            @Named(TaskExecutors.IO) ExecutorService executor,
            HttpClient httpClient,
            TeConfiguration configuration,
            AsyncProfilerHelper asyncProfilerHelper,
            AsyncProfilerHelper.AsyncProfilerConfiguration asyncProfilerConfiguration,
            HotspotConfiguration hotspotConfiguration,
            ObjectMapper objectMapper
    ) {
        TeInfrastructure create(OciLocation location, Path logDirectory) {
            return new TeInfrastructure(this, location, logDirectory);
        }
    }

    private enum DockerServer {
        SERVER("te-server", "10.0.0.2", "server"),
        DATABASE("te-database", "10.0.0.4", "database"),
        CLIENT("te-client", "10.0.0.3", "client"),
        TOOLSET("te-toolset", "10.0.0.100", null);

        final String instanceType;
        final String ip;
        @Nullable
        final String toolsetArg;

        DockerServer(String instanceType, String ip, @Nullable String toolsetArg) {
            this.instanceType = instanceType;
            this.ip = ip;
            this.toolsetArg = toolsetArg;
        }
    }

    private record DockerServerRuntime(
            Compute.Instance instance,
            OutputListener.Write log
    ) {
    }
}
