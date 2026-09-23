package io.micronaut.benchmark.loadgen.oci;

import io.hyperfoil.client.RestClient;
import io.hyperfoil.controller.Client;
import io.hyperfoil.controller.model.RequestStatisticsResponse;
import io.hyperfoil.controller.model.RequestStats;
import io.hyperfoil.http.statistics.HttpStats;
import io.micronaut.benchmark.api.BenchmarkStats;
import io.micronaut.benchmark.api.InstanceType;
import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.resource.AbstractDecoratedResource;
import io.micronaut.benchmark.loadgen.oci.resource.PhasedResource;
import io.micronaut.benchmark.loadgen.oci.resource.ResourceContext;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This class manages the provisioning of a hyperfoil cluster and allows using it for benchmarks.
 */
public final class HyperfoilRunner extends PhasedResource<HyperfoilRunner.HyperfoilPhase> {
    private static final Logger LOG = LoggerFactory.getLogger(HyperfoilRunner.class);

    /**
     * IP of the hyperfoil controller.
     */
    private static final String HYPERFOIL_CONTROLLER_IP = "10.0.0.3";
    /**
     * IP prefix of the hyperfoil agents.
     */
    private static final String HYPERFOIL_AGENT_PREFIX = "10.0.1.";
    /**
     * {@link Compute} instance type name for hyperfoil agents.
     */
    private static final String AGENT_INSTANCE_TYPE = "hyperfoil-agent";

    private static final long MAX_AGENT_LOG_SIZE = 8 * 1024 * 1024;

    private final Factory factory;
    private final Path logDirectory;

    private final Compute.Launch controllerLaunch;
    private final List<PhaseLock> controllerLocks;
    private final List<AgentResource> agents = new ArrayList<>();
    private final List<PhaseLock> agentLocks = new ArrayList<>();

    private ResilientSshPortForwarder controllerPortForward;
    private RestClient client;

    static {
        // 30s is too short for agent log download
        System.setProperty("io.hyperfoil.cli.request.timeout", "60000");
    }

    private HyperfoilRunner(Factory factory, Path logDirectory, AbstractInfrastructure infrastructure) throws Exception {
        super(factory.context);
        this.factory = factory;
        this.logDirectory = logDirectory;

        try {
            Files.createDirectories(logDirectory);
        } catch (FileAlreadyExistsException ignored) {}

        controllerLaunch = infrastructure.computeBuilder("hyperfoil-controller")
                .privateIp(HYPERFOIL_CONTROLLER_IP)
                .systemdCredential(Path.of("/etc/credstore/hyperfoil-controller/id_rsa"), factory.sshFactory.privateKeyBytes())
                .nixosConfiguration("hyperfoil-controller");
        controllerLocks = controllerLaunch.resource().require();
        for (int i = 0; i < factory.config.agentCount; i++) {
            OutputListener.Write log = new OutputListener.Write(Files.newOutputStream(logDirectory.resolve("agent-instance-" + i + ".log")));
            Compute.Launch launch = infrastructure.computeBuilder(AGENT_INSTANCE_TYPE)
                    .privateIp(agentIp(i))
                    .nixosConfiguration("hyperfoil-agent")
                    .consoleHistory(log);
            AgentResource r = new AgentResource(context, launch, log);
            r.name("agent" + i);
            agents.add(r);
            agentLocks.addAll(r.require());
        }

    }

    @Override
    protected List<HyperfoilPhase> phases() {
        return List.of(HyperfoilPhase.values());
    }

    public void manage() throws Exception {
        try {
            setPhase(HyperfoilPhase.AWAITING_CONTROLLER);
            Compute.InstanceResource controller = controllerLaunch.launchAsResource();
            for (AgentResource agent : agents) {
                AbstractInfrastructure.launch(agent, agent::manage);
            }

            PhaseLock.awaitAll(controllerLocks);
            setPhase(HyperfoilPhase.SETTING_UP_CONTROLLER);

            try (CommandRunner controllerSession = controller.connectSsh()) {
                try (ResilientSshPortForwarder controllerPortForward = factory.resilientForwarderFactory.create(
                              controller::connectSsh,
                              new SshdSocketAddress("localhost", 8090)
                      );
                     RestClient client = new RestClient(
                             factory.vertx,
                             controllerPortForward.address().getHostName(),
                             controllerPortForward.address().getPort(),
                             false, true, null)) {

                    setPhase(HyperfoilPhase.AWAITING_AGENTS);
                    PhaseLock.awaitAll(agentLocks);

                    this.controllerPortForward = controllerPortForward;
                    this.client = client;
                    setPhase(HyperfoilPhase.READY);
                    awaitUnlocked(HyperfoilPhase.READY);
                    this.controllerPortForward = null;
                    this.client = null;

                    setPhase(HyperfoilPhase.TERMINATING);
                }
            }
        } finally {
            for (PhaseLock lock : controllerLocks) {
                lock.close();
            }
            for (PhaseLock lock : agentLocks) {
                lock.close();
            }
            setPhase(HyperfoilPhase.TERMINATED);
        }
    }

    public PhaseLock require() {
        return lock(HyperfoilPhase.READY);
    }

    /**
     * Execute exactly one supplied workload; cancellation must stop its remote load.
     */
    public void benchmark(Path outputDirectory, Path workload, PhaseTracker.PhaseUpdater progress) throws Exception {
        awaitPhase(HyperfoilPhase.READY);
        String effective = benchmarkDefinition(workload);
        Files.writeString(outputDirectory.resolve("hyperfoil-effective.yaml"), effective);
        Client.BenchmarkRef benchmarkRef = client.register(effective, benchmarkData(workload), null, null);
        Client.RunRef runRef = benchmarkRef.start("run", Map.of());
        try {
            collectRun(outputDirectory, runRef, progress);
        } finally {
            // A disconnected CLI does not interrupt this thread; explicit cancellation does.
            boolean interrupted = Thread.interrupted();
            try {
                try {
                    if (!"TERMINATED".equals(runRef.statsRecent().status)) {
                        runRef.kill();
                        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
                        while (!"TERMINATED".equals(runRef.statsRecent().status)) {
                            if (System.nanoTime() > deadline) {
                                throw new TimeoutException("Remote load did not terminate");
                            }
                            TimeUnit.SECONDS.sleep(1);
                        }
                    }
                } catch (Exception e) {
                    throw new EnvironmentInvalidException("Cannot confirm that the remote load stopped", e);
                }
            } finally {
                try {
                    byte[] bytes = runRef.statsAll("json");
                    if (!Files.exists(outputDirectory.resolve("output.json")) && !Files.exists(outputDirectory.resolve("output-failed.json"))) {
                        Files.write(outputDirectory.resolve("output-failed.json"), bytes);
                    }
                } catch (Exception e) {
                    LOG.warn("Could not salvage Hyperfoil statistics", e);
                }
                downloadAgentLogs(outputDirectory);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void collectRun(Path outputDirectory, Client.RunRef runRef, PhaseTracker.PhaseUpdater progress) throws Exception {

        BenchmarkPhase benchmarkPhase = BenchmarkPhase.BENCHMARKING;

        progress.update(benchmarkPhase);
        long startTime = System.nanoTime();
        String lastPhase = null;
        while (true) {
            RequestStatisticsResponse recentStats = Infrastructure.retry(runRef::statsRecent, controllerPortForward::disconnect);
            if (recentStats.status.equals("TERMINATED")) {
                break;
            }
            if (recentStats.status.equals("INITIALIZING")) {
                if (System.nanoTime() - startTime > TimeUnit.MINUTES.toNanos(5)) {
                    throw new TimeoutException("Benchmark stuck too long in INITIALIZING state");
                }
            }
            StringBuilder log = new StringBuilder("Benchmark progress: ").append(recentStats.status);
            for (RequestStats statistic : recentStats.statistics) {
                log.append(' ').append(statistic.metric).append(':').append(statistic.phase).append(":mean=").append(statistic.summary.meanResponseTime);
                if (!Objects.equals(statistic.phase, lastPhase)) {
                    lastPhase = statistic.phase;
                    progress.update(benchmarkPhase, 0, statistic.phase);
                }
            }
            LOG.info("{}", log);
            TimeUnit.SECONDS.sleep(5);
        }

        record StatsAllWrapper(byte[] resultBytes, BenchmarkStats statsAll) {
        }

        StatsAllWrapper wrapper = Infrastructure.retry(() -> {
            byte[] bytes = runRef.statsAll("json");
            return new StatsAllWrapper(bytes, factory.objectMapper.readValue(bytes, BenchmarkStats.class));
        }, controllerPortForward::disconnect);
        List<String> benchmarkFailures = new ArrayList<>();
        boolean invalidatesBenchmark = false;
        for (BenchmarkStats.Info.Error error : wrapper.statsAll.info().errors()) {
            if (error.msg().contains("Jitter watchdog was not invoked")) {
                LOG.warn("Jitter in watchdog agent. Log message: {}", error.msg());
                continue;
            }
            benchmarkFailures.add(error.agent() + ": " + error.msg());
        }
        for (BenchmarkStats.SlaFailure failure : wrapper.statsAll.failures()) {
            LOG.info("SLA failure: {}", failure);
            if (failure.phase().equals("warmup")) {
                benchmarkFailures.add("SLA failure in " + failure.phase() + " phase: " + failure.message());
                invalidatesBenchmark = true;
            }
        }
        for (BenchmarkStats.Stats stats : wrapper.statsAll.stats()) {
            if (stats.total().summary().responseCount == 0) {
                benchmarkFailures.add("No responses in phase " + stats.name());
                invalidatesBenchmark = true;
            }
            if (stats.total().summary().invalid > 0 || stats.total().summary().requestTimeouts > 0
                    || stats.total().summary().connectionErrors > 0 || stats.total().summary().internalErrors > 0) {
                benchmarkFailures.add("Request failures in phase " + stats.name());
                invalidatesBenchmark = true;
            }
        }

        LOG.info("Benchmark complete, writing output");
        Path outputPath = outputDirectory.resolve(benchmarkFailures.isEmpty() ? "output.json" : "output-failed.json");
        Files.write(outputPath, wrapper.resultBytes);
        Path metaPath = outputDirectory.resolve(benchmarkFailures.isEmpty() ? "meta.json" : "meta-failed.json");
        Files.write(metaPath, factory.objectMapper.writeValueAsBytes(new Metadata(factory.config)));
        if (!benchmarkFailures.isEmpty()) {
            String msg = String.join("\n", benchmarkFailures) + "\nOutput written at: " + outputPath;
            throw invalidatesBenchmark ? new InvalidatesBenchmarkException(msg) : new Exception(msg);
        }
    }

    private void downloadAgentLogs(Path outputDirectory) {
        LOG.info("Downloading agent logs…");
        try {
            for (String agent : Infrastructure.retry(client::agents, controllerPortForward::disconnect)) {
                Infrastructure.retry(() -> {
                    Path dest = outputDirectory.resolve(agent.replaceAll("[^0-9a-zA-Z]", "") + ".log");
                    client.downloadLog(agent, null, 0, MAX_AGENT_LOG_SIZE, dest.toFile());
                    try {
                        if (Files.size(dest) >= MAX_AGENT_LOG_SIZE) {
                            LOG.warn("Agent log {} size exceeded limit of {} bytes", dest, MAX_AGENT_LOG_SIZE);
                        }
                    } catch (IOException e) {
                        LOG.warn("Failed to get agent log size", e);
                    }
                    return null;
                }, controllerPortForward::disconnect);
            }
        } catch (Exception e) {
            LOG.warn("Failed to download agent logs", e);
        }
    }

    private static String agentIp(int i) {
        return HYPERFOIL_AGENT_PREFIX + (i + 1);
    }

    private Map<String, Object> runtimeAgents() {
        InstanceType agentInstanceType = factory.compute.getInstanceType(AGENT_INSTANCE_TYPE);
        Map<String, Object> agents = new LinkedHashMap<>();
        for (int i = 0; i < factory.config.agentCount; i++) {
            String extras = "-Dio.hyperfoil.cpu.watchdog.period=10000 -XX:+TieredCompilation -XX:TieredStopAtLevel=1 -XX:+UseZGC -Xmx" + ((int) (agentInstanceType.memoryInGb() * 0.8)) + "G";
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("host", agentIp(i));
            agent.put("port", 22);
            agent.put("threads", (int) agentInstanceType.ocpus() - 1);
            agent.put("extras", extras);
            agent.put("user", "root");
            agents.put("agent" + i, agent);
        }
        return agents;
    }

    private static Map<String, byte[]> benchmarkData(Path workload) throws IOException {
        Path directory = workload.resolveSibling("hyperfoil-data");
        if (!Files.exists(directory)) {
            return Map.of();
        }
        Map<String, byte[]> data = new LinkedHashMap<>();
        try (var files = Files.walk(directory.toRealPath())) {
            Path root = directory.toRealPath();
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                data.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return data;
    }

    private String benchmarkDefinition(Path workload) throws Exception {
        Map<String, Object> definition = yamlMap(yaml().load(Files.readString(workload)), "Hyperfoil benchmark definition");
        if (!(definition.get("name") instanceof String)) {
            throw new IllegalArgumentException("Expected Hyperfoil benchmark name to be a string");
        }
        definition.put("name", "benchmark-" + UUID.randomUUID());

        Map<String, Object> configuredAgents = yamlMap(definition.get("agents"), "Hyperfoil agents");
        if (!configuredAgents.isEmpty()) {
            throw new IllegalArgumentException("Expected an empty Hyperfoil agent mapping");
        }
        definition.put("agents", runtimeAgents());

        return yaml().dump(definition);
    }

    private static Yaml yaml() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setIndent(2);
        dumperOptions.setWidth(120);
        dumperOptions.setSplitLines(false);
        return new Yaml(new SafeConstructor(loaderOptions), new Representer(dumperOptions), dumperOptions, loaderOptions);
    }

    private static Map<String, Object> yamlMap(Object value, String description) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected " + description + " to be a mapping");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Expected string key in " + description);
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private final class AgentResource extends AbstractDecoratedResource {
        private final Compute.Launch launch;
        private final List<PhaseLock> instanceLocks;
        private Compute.InstanceResource instance;
        private final OutputListener.Write log;

        public AgentResource(ResourceContext context, Compute.Launch launch, OutputListener.Write log) {
            super(context);
            this.launch = launch;
            this.log = log;
            instanceLocks = launch.resource().require();
            dependOn(instanceLocks);
        }

        @Override
        protected void launchDependencies() {
            instance = launch.launchAsResource();
        }

        @Override
        protected void tearDown() {
            try (log) {
                for (PhaseLock instanceLock : instanceLocks) {
                    instanceLock.close();
                }
                instance.awaitTermination();
            } catch (Exception e) {
                LOG.warn("Failed to close agent", e);
            }
        }
    }

    @Singleton
    static final class Factory {
        private final ResourceContext context;
        private final Compute compute;
        private final SshFactory sshFactory;
        private final HyperfoilConfiguration config;
        private final ObjectMapper objectMapper;
        private final ResilientSshPortForwarder.Factory resilientForwarderFactory;
        private final Vertx vertx;

        Factory(ResourceContext context, Compute compute, SshFactory sshFactory, HyperfoilConfiguration config, ObjectMapper objectMapper, ResilientSshPortForwarder.Factory resilientForwarderFactory) {
            this.context = context;
            this.compute = compute;
            this.sshFactory = sshFactory;
            this.config = config;
            this.objectMapper = objectMapper.rebuild()
                    .registerSubtypes(HttpStats.class)
                    .build();
            this.resilientForwarderFactory = resilientForwarderFactory;
            this.vertx = Vertx.vertx();

        }

        @PreDestroy
        void close() {
            vertx.close().toCompletionStage().toCompletableFuture().join();
        }

        public HyperfoilRunner create(Path outputDirectory, AbstractInfrastructure infrastructure) throws Exception {
            return new HyperfoilRunner(this, outputDirectory, infrastructure);
        }
    }

    @ConfigurationProperties("hyperfoil")
    public record HyperfoilConfiguration(int agentCount) {
    }

    private record Metadata(HyperfoilConfiguration hyperfoilConfiguration) {}

    protected enum HyperfoilPhase {
        AWAITING_CONTROLLER,
        SETTING_UP_CONTROLLER,
        AWAITING_AGENTS,
        READY,
        TERMINATING,
        TERMINATED
    }
}
