package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oracle.bmc.http.client.pki.Pem;
import io.hyperfoil.api.config.Benchmark;
import io.hyperfoil.api.config.BenchmarkBuilder;
import io.hyperfoil.api.config.SLABuilder;
import io.hyperfoil.api.config.ScenarioBuilder;
import io.hyperfoil.api.statistics.StatisticsSummary;
import io.hyperfoil.client.RestClient;
import io.hyperfoil.controller.Client;
import io.hyperfoil.controller.model.RequestStatisticsResponse;
import io.hyperfoil.controller.model.RequestStats;
import io.hyperfoil.core.util.ConstantBytesGenerator;
import io.hyperfoil.http.config.ConnectionStrategy;
import io.hyperfoil.http.config.HttpBuilder;
import io.hyperfoil.http.config.HttpPluginBuilder;
import io.hyperfoil.http.statistics.HttpStats;
import io.hyperfoil.http.steps.HttpRequestStepBuilder;
import io.hyperfoil.http.steps.HttpStepCatalog;
import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.resource.AbstractDecoratedResource;
import io.micronaut.benchmark.loadgen.oci.resource.PhasedResource;
import io.micronaut.benchmark.loadgen.oci.resource.ResourceContext;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.http.ssl.CertificateProvider;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.vertx.core.Vertx;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

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

    private X509Certificate mtlsCert;
    private PrivateKey mtlsKey;

    private CommandRunner controllerSession;
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
            AgentResource r = new AgentResource(context, i, launch, log);
            r.name("agent" + i);
            agents.add(r);
            agentLocks.addAll(r.require());
        }

        if (factory.config.mtls != null) {
            CertificateProvider provider = factory.certificateProviders.get(Qualifiers.byName(factory.config.mtls));
            Flux.from(provider.getKeyStore()).subscribe(keyStore -> {
                try {
                    Enumeration<String> aliases = keyStore.aliases();
                    while (aliases.hasMoreElements()) {
                        String alias = aliases.nextElement();
                        if (keyStore.isKeyEntry(alias)) {
                            mtlsKey = (PrivateKey) keyStore.getKey(alias, "".toCharArray());
                            mtlsCert = (X509Certificate) keyStore.getCertificate(alias);
                            break;
                        }
                    }
                } catch (Exception e) {
                    LOG.error("Error loading key store", e);
                }
            });
        } else {
            mtlsCert = null;
            mtlsKey = null;
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

                    this.controllerSession = controllerSession;
                    this.controllerPortForward = controllerPortForward;
                    this.client = client;
                    setPhase(HyperfoilPhase.READY);
                    awaitUnlocked(HyperfoilPhase.READY);
                    this.controllerSession = null;
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
     * Create a new benchmark closure to run benchmarks for a given request definition.
     *
     * @param outputDirectory The output directory for benchmark results
     * @param protocol        The HTTP protocol settings to use
     * @param body            The HTTP request
     * @return The benchmark closure
     */
    public FrameworkRun.BenchmarkClosure benchmarkClosure(Path outputDirectory, ProtocolSettings protocol, SuiteRequest body) {
        return new FrameworkRun.BenchmarkClosure() {
            @Override
            public void benchmark(PhaseTracker.PhaseUpdater progress) throws Exception {
                HyperfoilRunner.this.benchmark(outputDirectory, protocol, body, progress, false);
            }

            @Override
            public void pgoLoad(PhaseTracker.PhaseUpdater progress) throws Exception {
                HyperfoilRunner.this.benchmark(outputDirectory, protocol, body, progress, true);
            }
        };
    }

    private String createCurlCommand(Protocol protocol, SuiteRequest definition, String socketUri, boolean verbose) throws CertificateEncodingException {
        StringBuilder builder = new StringBuilder("curl");
        builder.append(protocol == Protocol.HTTP1 ? " --http1.1" : " --http2");
        builder.append(" --insecure");
        builder.append(" --max-time 20");
        if (mtlsCert != null) {
            builder.append(" --cert <(echo '");
            builder.append(Base64.getEncoder().encodeToString(Pem.encoder().encode(mtlsCert).getBytes(StandardCharsets.UTF_8)));
            builder.append("' | base64 -d)");
        }
        if (mtlsKey != null) {
            builder.append(" --key <(echo '");
            builder.append(Base64.getEncoder().encodeToString(Pem.encoder().encode(mtlsKey)));
            builder.append("' | base64 -d)");
        }
        builder.append(verbose ? " -v" : " --silent");
        builder.append(" -X ").append(definition.method().name());
        builder.append(" --request-target '").append(definition.uri()).append("'");
        builder.append(" -H 'host: ").append(definition.host()).append("'");
        if (definition.requestBody() != null) {
            builder.append(" -H 'content-type: ").append(definition.requestType()).append("'");
            builder.append(" -d '").append(definition.requestBody()).append("'");
        }
        definition.requestHeaders().forEach((key, value) -> builder.append(" -H '").append(key).append(": ").append(value).append("'"));
        builder.append(' ').append(socketUri);
        return builder.toString();
    }

    private void benchmark(Path outputDirectory, ProtocolSettings protocol, SuiteRequest body, PhaseTracker.PhaseUpdater progress, boolean forPgo) throws Exception {
        awaitPhase(HyperfoilPhase.READY);

        BenchmarkPhase benchmarkPhase = forPgo ? BenchmarkPhase.PGO : BenchmarkPhase.BENCHMARKING;

        progress.update(benchmarkPhase);
        String ip = Infrastructure.SERVER_IP;
        int port = protocol.protocol() == Protocol.HTTP1 ? 8080 : 8443;
        io.hyperfoil.http.config.Protocol prot = protocol.protocol() == Protocol.HTTP1 ? io.hyperfoil.http.config.Protocol.HTTP : io.hyperfoil.http.config.Protocol.HTTPS;

        String socketUri = prot.scheme + "://" + ip + ":" + port;
        SuiteRequest statusRequest = factory.statusRequest;
        Infrastructure.retry(() -> {
            try (OutputListener.Write write = new OutputListener.Write(Files.newOutputStream(outputDirectory.resolve(statusRequest.name() + ".http")))) {
                SshUtil.run(controllerSession, createCurlCommand(protocol.protocol(), statusRequest, socketUri, true), write);
            }
            return null;
        }, controllerPortForward::disconnect);
        Infrastructure.retry(() -> {
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            try (OutputListener.Write write = new OutputListener.Write(resp)) {
                SshUtil.run(controllerSession, createCurlCommand(protocol.protocol(), body, socketUri, false), write);
            } catch (Exception e) {
                throw new RuntimeException("Failed to query benchmark output. Body: '" + resp.toString(StandardCharsets.UTF_8) + "'", e);
            }
            boolean matches = switch (body.responseMatchingMode()) {
                case EQUAL -> Arrays.equals(resp.toByteArray(), body.responseBody().getBytes(StandardCharsets.UTF_8));
                case JSON -> factory.objectMapper.readTree(resp.toByteArray()).equals(factory.objectMapper.readTree(body.responseBody()));
                case REGEX -> Pattern.compile(body.responseBody()).matcher(resp.toString(StandardCharsets.UTF_8)).matches();
            };
            if (!matches) {
                throw new InvalidatesBenchmarkException("Response to test request was incorrect: " + resp.toString(StandardCharsets.UTF_8));
            }
            return null;
        });

        String name = "benchmark-" + UUID.randomUUID();
        BenchmarkBuilder benchmark = BenchmarkBuilder.builder()
                .name(name)
                .failurePolicy(Benchmark.FailurePolicy.CANCEL);
        BenchmarkMetadata.InstanceType agentInstanceType = factory.compute.getInstanceType(AGENT_INSTANCE_TYPE);
        for (int i = 0; i < factory.config.agentCount; i++) {
            String extras = "-Dio.hyperfoil.cpu.watchdog.period=10000 -XX:+TieredCompilation -XX:TieredStopAtLevel=1 -XX:+UseZGC -Xmx" + ((int) (agentInstanceType.memoryInGb() * 0.8)) + "G";
            AsyncProfilerHelper.Session asyncProfilerSession = agents.get(i).asyncProfilerSession;
            if (asyncProfilerSession != null) {
                extras += " " + asyncProfilerSession.getJvmArgument();
            }
            benchmark.addAgent("agent" + i, agentIp(i) + ":22", Map.of(
                    "threads", String.valueOf((int) agentInstanceType.ocpus() - 1),
                    "extras", extras,
                    "user", "root"
            ));
        }

        HttpBuilder httpBuilder = benchmark.addPlugin(HttpPluginBuilder::new)
                .http()
                .protocol(prot)
                .host(ip)
                .port(port)
                .allowHttp1x(protocol.protocol() != Protocol.HTTPS2)
                .allowHttp2(protocol.protocol() == Protocol.HTTPS2)
                .sharedConnections(protocol.sharedConnections())
                .connectionStrategy(ConnectionStrategy.SHARED_POOL)
                .pipeliningLimit(protocol.pipeliningLimit())
                .maxHttp2Streams(protocol.maxHttp2Streams())
                .sslHandshakeTimeout(TimeUnit.MINUTES.toMillis(1))
                .useHttpCache(false); // caching is expensive, disable it

        if (mtlsCert != null) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("default", mtlsKey, "".toCharArray(), new Certificate[]{mtlsCert});
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ks.store(baos, "".toCharArray());
            httpBuilder.keyManager()
                    .storeBytes(baos.toByteArray())
                    .password("");
        }

        List<String> phaseNames = new ArrayList<>();
        if (!forPgo) {
            phaseNames.add("warmup");
            prepareScenario(body, ip, port, benchmark.addPhase("warmup")
                    .constantRate(protocol.compileOps())
                    .maxSessions((int) (protocol.compileOps() * factory.config.sessionLimitFactor))
                    .duration(TimeUnit.MILLISECONDS.convert(factory.config.warmupDuration))
                    .isWarmup(true)
                    .scenario());
            String lastPhase = "warmup";
            for (int i = 0; i < protocol.ops().size(); i++) {
                int ops = protocol.ops().get(i);
                String phaseName = "main/" + i;
                phaseNames.add(phaseName);
                SLABuilder<?>.LimitsBuilder limits = prepareScenario(body, ip, port, benchmark.addPhase(phaseName)
                        .constantRate(0)
                        .usersPerSec(ops)
                        .maxSessions(Math.min((int) (ops * factory.config.sessionLimitFactor), protocol.sharedConnections()))
                        .duration(TimeUnit.MILLISECONDS.convert(factory.config.benchmarkDuration))
                        .isWarmup(false)
                        .startAfter(lastPhase)
                        .scenario())
                        .sla().addItem().limits();
                for (Map.Entry<Double, Duration> e : protocol.sla().entrySet()) {
                    limits.add(e.getKey(), e.getValue().toNanos());
                }
                lastPhase = phaseName;
            }
        } else {
            phaseNames.add("pgo");
            prepareScenario(body, ip, port, benchmark.addPhase("pgo")
                    .constantRate(protocol.compileOps())
                    .maxSessions((int) (protocol.compileOps() * factory.config.sessionLimitFactor))
                    .duration(TimeUnit.MILLISECONDS.convert(factory.config.pgoDuration))
                    .isWarmup(false)
                    .scenario());
        }

        Benchmark builtBenchmark = benchmark.build();

        Client.BenchmarkRef benchmarkRef = client.register(builtBenchmark, null);
        Client.RunRef runRef = benchmarkRef.start("run", Map.of());
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
            StringBuilder log = new StringBuilder("Benchmark progress").append(forPgo ? " (PGO): " : ": ").append(recentStats.status);
            for (RequestStats statistic : recentStats.statistics) {
                log.append(' ').append(statistic.metric).append(':').append(statistic.phase).append(":mean=").append(statistic.summary.meanResponseTime);
                if (!Objects.equals(statistic.phase, lastPhase)) {
                    lastPhase = statistic.phase;
                    double progressPercent = (phaseNames.indexOf(statistic.phase) + 1.0) / (phaseNames.size() + 1);
                    progress.update(benchmarkPhase, progressPercent, statistic.phase);
                }
            }
            LOG.info("{}", log);
            TimeUnit.SECONDS.sleep(5);
        }

        record StatsAllWrapper(byte[] resultBytes, StatsAll statsAll) {}

        StatsAllWrapper wrapper = Infrastructure.retry(() -> {
            byte[] bytes = runRef.statsAll("json");
            return new StatsAllWrapper(bytes, factory.objectMapper.readValue(bytes, StatsAll.class));
        }, controllerPortForward::disconnect);
        List<String> benchmarkFailures = new ArrayList<>();
        boolean invalidatesBenchmark = false;
        for (StatsAll.Info.Error error : wrapper.statsAll.info.errors) {
            if (error.msg.contains("Jitter watchdog was not invoked")) {
                LOG.warn("Jitter in watchdog agent. Log message: {}", error.msg);
                continue;
            }
            benchmarkFailures.add(error.agent + ": " + error.msg);
        }
        for (StatsAll.SlaFailure failure : wrapper.statsAll.failures) {
            LOG.info("SLA failure: {}", failure);
            if (failure.phase.equals("pgo") || failure.phase.equals("warmup")) {
                benchmarkFailures.add("SLA failure in " + failure.phase + " phase: " + failure.message);
                invalidatesBenchmark = true;
            }
        }
        for (StatsAll.Stats stats : wrapper.statsAll.stats) {
            if (stats.total.summary.responseCount == 0) {
                benchmarkFailures.add("No responses in phase " + stats.name);
                invalidatesBenchmark = true;
            }
        }

        if (!forPgo || !benchmarkFailures.isEmpty()) {
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
    }

    private static HttpRequestStepBuilder prepareScenario(SuiteRequest sampleRequest, String ip, int port, ScenarioBuilder warmup) {
        HttpRequestStepBuilder builder = warmup.initialSequence("test")
                .step(HttpStepCatalog.class)
                .httpRequest(sampleRequest.method())
                .authority(ip + ":" + port)
                .path(sampleRequest.uri())
                .body(sampleRequest.requestBody() == null ? null : new ConstantBytesGenerator(sampleRequest.requestBody().getBytes(StandardCharsets.UTF_8)));
        HttpRequestStepBuilder.HeadersBuilder headers = builder.headers();
        // MUST be lowercase for HTTP/2
        headers.header("content-type", sampleRequest.requestType());
        sampleRequest.requestHeaders().forEach((header, value) -> headers.header(header.toLowerCase(Locale.ROOT), value));
        return builder;
    }

    private static String agentIp(int i) {
        return HYPERFOIL_AGENT_PREFIX + (i + 1);
    }

    private final class AgentResource extends AbstractDecoratedResource {
        private final int i;
        private final Compute.Launch launch;
        private Compute.InstanceResource instance;
        private final OutputListener.Write log;
        private AsyncProfilerHelper.Session asyncProfilerSession;

        public AgentResource(ResourceContext context, int i, Compute.Launch launch, OutputListener.Write log) {
            super(context);
            this.i = i;
            this.launch = launch;
            this.log = log;
            dependOn(launch.resource().require());
        }

        @Override
        protected void launchDependencies() throws Exception {
            instance = launch.launchAsResource();
        }

        @Override
        protected void setUp() throws Exception {
            if (factory.config.agentAsyncProfiler) {
                try (CommandRunner agentSession = instance.connectSsh()) {
                    AsyncProfilerHelper.Session session = factory.asyncProfilerHelper.createSession(log);
                    session.initAgent(agentSession);
                    asyncProfilerSession = session;
                }
            }
        }

        @Override
        protected void tearDown() {
            try (log) {
                if (asyncProfilerSession != null) {
                    Path dir = logDirectory.resolve("agent" + i);
                    try {
                        Files.createDirectories(dir);
                    } catch (FileAlreadyExistsException ignored) {}
                    try (CommandRunner agentSession = instance.connectSsh()) {
                        asyncProfilerSession.finish(agentSession, dir);
                    } catch (Exception e) {
                        LOG.error("Failed to download agent profiler results", e);
                    }
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
        private final SuiteRequest statusRequest;
        private final AsyncProfilerHelper asyncProfilerHelper;
        private final ObjectMapper objectMapper;
        private final ResilientSshPortForwarder.Factory resilientForwarderFactory;
        private final Vertx vertx;
        private final BeanProvider<CertificateProvider> certificateProviders;

        Factory(ResourceContext context, Compute compute, SshFactory sshFactory, HyperfoilConfiguration config, BenchmarkMetadata metadata, AsyncProfilerHelper asyncProfilerHelper, ObjectMapper objectMapper, ResilientSshPortForwarder.Factory resilientForwarderFactory, BeanProvider<CertificateProvider> certificateProviders) {
            this.context = context;
            this.compute = compute;
            this.sshFactory = sshFactory;
            this.config = config;
            this.statusRequest = metadata.suite().statusRequest();
            this.asyncProfilerHelper = asyncProfilerHelper;
            this.objectMapper = objectMapper.rebuild()
                    .registerSubtypes(HttpStats.class)
                    .build();
            this.resilientForwarderFactory = resilientForwarderFactory;
            this.certificateProviders = certificateProviders;
            this.vertx = Vertx.vertx();

        }

        public HyperfoilRunner create(Path outputDirectory, AbstractInfrastructure infrastructure) throws Exception {
            return new HyperfoilRunner(this, outputDirectory, infrastructure);
        }
    }

    /**
     * @param agentCount         Number of agents to create
     * @param warmupDuration     Duration of the warmup run
     * @param benchmarkDuration  Duration of each main benchmark run
     * @param pgoDuration        Duration of the PGO run
     * @param sessionLimitFactor Factor of the hyperfoil session limit. If a particular run is 1000 ops/s, and this
     *                           factor is 2, the maximum number of hyperfoil sessions is 2000.
     */
    @ConfigurationProperties("hyperfoil")
    public record HyperfoilConfiguration(
            int agentCount,
            Duration warmupDuration,
            Duration benchmarkDuration,
            Duration pgoDuration,
            double sessionLimitFactor,
            @Nullable String mtls,
            boolean agentAsyncProfiler
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatsAll(
            Info info,
            List<SlaFailure> failures,
            List<Stats> stats
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Info(
                List<Error> errors
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            public record Error(
                    String agent,
                    String msg
            ) {

            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record SlaFailure(
                String phase,
                String message
        ) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Stats(
                String phase,
                String name,
                Total total,
                Histogram histogram
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            public record Total(StatisticsSummary summary) {
            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Histogram(
                List<Percentile> percentiles
        ) {
        }

        public record Percentile(
                double from,
                double to,
                double percentile,
                long count,
                long totalCount
        ) {
        }

        @Nullable
        public Stats findPhase(String name) {
            for (Stats phase : stats) {
                if (phase.name.equals(name)) {
                    return phase;
                }
            }
            return null;
        }

        @Nullable
        public Stats findPhaseContaining(Instant time) {
            for (Stats phase : stats) {
                if (phase.total.summary.startTime < time.toEpochMilli() && phase.total.summary.endTime > time.toEpochMilli()) {
                    return phase;
                }
            }
            return null;
        }
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
