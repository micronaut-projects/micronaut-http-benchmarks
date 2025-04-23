package io.micronaut.benchmark.loadgen.oci.techempower;

import com.oracle.bmc.bastion.BastionClient;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import io.micronaut.benchmark.loadgen.oci.AbstractInfrastructure;
import io.micronaut.benchmark.loadgen.oci.Compute;
import io.micronaut.benchmark.loadgen.oci.OciLocation;
import io.micronaut.benchmark.loadgen.oci.OutputListener;
import io.micronaut.benchmark.loadgen.oci.PhaseTracker;
import io.micronaut.benchmark.loadgen.oci.RegionalClient;
import io.micronaut.benchmark.loadgen.oci.SshFactory;
import io.micronaut.benchmark.loadgen.oci.SshUtil;
import io.micronaut.benchmark.loadgen.oci.SutMonitor;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Stream;

final class TeInfrastructure extends AbstractInfrastructure {
    private static final Logger LOG = LoggerFactory.getLogger(TeInfrastructure.class);
    private final Factory factory;

    private final Map<DockerServer, DockerServerRuntime> dockerServers = new EnumMap<>(DockerServer.class);

    private TeInfrastructure(Factory factory, OciLocation location, Path logDirectory) {
        super(location, logDirectory, factory.vcnClient, factory.computeClient, factory.bastionClient, factory.compute);
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

                try (ClientSession session = instance.instance.connectSsh()) {
                    // set up docker on the main runtime servers
                    SshUtil.openFirewallPorts(session, instance.log);

                    SshUtil.run(session, "sudo dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo", instance.log);

                    SshUtil.run(session, "sudo mkdir -p /etc/systemd/system/docker.socket.d", instance.log);
                    // tcp listener on port 2375
                    SshUtil.run(session, "echo \"[Socket]\nListenStream=\nListenStream=0.0.0.0:2375\nSocketMode=\" | sudo tee /etc/systemd/system/docker.socket.d/override.conf", instance.log);
                    SshUtil.run(session, "sudo yum install -y docker-ce git", instance.log);
                    SshUtil.run(session, "sudo systemctl start docker.socket", instance.log);
                }
                return null;
            }));
        }
        for (Future<?> setupFuture : setupFutures) {
            setupFuture.get();
        }
    }

    public void run(Path resultDirectory, List<Revision> revisions) throws Exception {
        Revision tefbRepoRevision = revisions.stream()
                .filter(r -> r.githubRepoName().equals(Revision.TEFB_NAME))
                .findAny().orElseThrow(() -> new IllegalArgumentException("Missing TEFB repo revision information"));

        StringBuilder toolsetCommand = new StringBuilder("cd " + tefbRepoRevision.folderName() + " && DOCKER_HOST=tcp://127.0.0.1:2375 ./tfb");
        for (DockerServer s : DockerServer.values()) {
            if (s.toolsetArg != null) {
                toolsetCommand.append(" --").append(s.toolsetArg).append("-host ").append(s.ip);
            }
        }
        toolsetCommand.append(" --network-mode host");
        toolsetCommand.append(" --test micronaut micronaut-graalvm");
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

        DockerServerRuntime toolset = dockerServers.get(DockerServer.TOOLSET);
        try (ClientSession session = toolset.instance.connectSsh()) {
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

            LOG.info("Patching settings.gradle");
            ScpClient scpClient = ScpClientCreator.instance().createScpClient(session);
            String settingsGradlePath = tefbRepoRevision.folderName() + "/frameworks/Java/micronaut/settings.gradle";
            StringBuilder settingsGradle = new StringBuilder(new String(scpClient.downloadBytes(settingsGradlePath), StandardCharsets.UTF_8));
            for (Revision revision : revisions) {
                if (revision.modulePrefix() != null) {
                    settingsGradle.append("""
                            \s
                            includeBuild("%s") {
                                def modules = file("%s/settings.gradle").readLines().findAll {
                                   it.startsWith('include "') && !it.startsWith('include ":test')
                                }.collect { it.substring(9) - '"' }
                                dependencySubstitution {
                                    modules.each { mod ->
                                        substitute module("%s${mod}") using project(":$mod")
                                    }
                                }
                            }
                            """.formatted(revision.folderName(), revision.folderName(), revision.modulePrefix()));
                }
            }
            Path settingsGradleLocal = resultDirectory.resolve("settings.gradle");
            Files.writeString(settingsGradleLocal, settingsGradle.toString());
            scpClient.upload(settingsGradleLocal, settingsGradlePath);

            LOG.info("Patching dockerfiles");
            for (Map.Entry<String, String> java17Install : Map.of(
                    "micronaut.dockerfile", "apt update && apt install -y openjdk-17-jdk-headless",
                    "micronaut-graalvm.dockerfile", "microdnf install java-17-openjdk-headless"
            ).entrySet()) {
                String path = tefbRepoRevision.folderName() + "/frameworks/Java/micronaut/" + java17Install.getKey();
                StringBuilder dockerfile = new StringBuilder(new String(scpClient.downloadBytes(path), StandardCharsets.UTF_8));
                dockerfile.insert(dockerfile.indexOf("\n") + 1, "RUN " + java17Install.getValue() + "\n");

                Path localPath = resultDirectory.resolve(java17Install.getKey());
                Files.writeString(localPath, dockerfile.toString());
                scpClient.upload(localPath, path);
            }

            LOG.info("Running benchmark");
            SshUtil.run(session, toolsetCommand.toString(), toolset.log);

            LOG.info("Downloading results");
            scpClient.download(tefbRepoRevision.folderName() + "/results", resultDirectory, ScpClient.Option.Recursive);
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
        Map response = factory.httpClient.toBlocking().retrieve(HttpRequest.POST("https://tfb-status.techempower.com/share/upload", resultBytes), Map.class);
        LOG.info("Result URL: {}", response.get("visualizeResultsUrl"));
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
            RegionalClient<ComputeClient> computeClient,
            RegionalClient<VirtualNetworkClient> vcnClient,
            RegionalClient<BastionClient> bastionClient,
            Compute compute,
            SshFactory sshFactory,
            SutMonitor sutMonitor,
            @Named(TaskExecutors.IO) ExecutorService executor,
            HttpClient httpClient
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
