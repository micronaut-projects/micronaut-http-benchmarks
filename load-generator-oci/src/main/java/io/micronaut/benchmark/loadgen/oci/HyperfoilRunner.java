package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.api.config.Benchmark;
import io.hyperfoil.api.config.BenchmarkBuilder;
import io.hyperfoil.api.config.SLABuilder;
import io.hyperfoil.api.config.ScenarioBuilder;
import io.hyperfoil.api.statistics.StatisticsSummary;
import io.hyperfoil.client.RestClient;
import io.hyperfoil.client.RestClientException;
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
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.scheduling.TaskExecutors;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClosedException;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * This class manages the provisioning of a hyperfoil cluster and allows using it for benchmarks.
 */
public final class HyperfoilRunner implements AutoCloseable {
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
     * Directory of the hyperfoil controller.
     */
    private static final String REMOTE_HYPERFOIL_LOCATION = "hyperfoil";
    /**
     * {@link Compute} instance type name for hyperfoil agents.
     */
    private static final String AGENT_INSTANCE_TYPE = "hyperfoil-agent";
    private static final String PROFILER_LOCATION = "/tmp/libasyncProfiler.so";

    private final Factory factory;
    private final CompletableFuture<SshFactory.Relay> relay = new CompletableFuture<>();
    private final CompletableFuture<Client> client = new CompletableFuture<>();
    private final CompletableFuture<Void> terminate = new CompletableFuture<>();
    private final Path logDirectory;
    private final HyperfoilInstances instances;
    /**
     * Worker thread that handles the actual provisioning and benchmarking.
     */
    private final Future<?> worker;
    private final List<Compute.Instance> computeInstances = new CopyOnWriteArrayList<>();
    private ClientSession controllerSession;
    private final CompletableFuture<ResilientSshPortForwarder> controllerPortForward = new CompletableFuture<>();

    private final X509Certificate mtlsCert;
    private final PrivateKey mtlsKey;

    static {
        // 30s is too short for agent log download
        System.setProperty("io.hyperfoil.cli.request.timeout", "60000");
    }

    private HyperfoilRunner(Factory factory, Path logDirectory, OciLocation location, String privateSubnetId) throws Exception {
        this.factory = factory;
        this.logDirectory = logDirectory;

        try {
            Files.createDirectories(logDirectory);
        } catch (FileAlreadyExistsException ignored) {}

        // fail fast if we can't create the instances
        instances = createInstances(location, privateSubnetId);
        worker = factory.executor.submit(MdcTracker.copyMdc(() -> {
            try (AutoCloseable ignored = this::terminateAndWait) {
                deploy();
            } catch (InterruptedException | InterruptedIOException ignored) {
            } catch (Exception e) {
                LOG.error("Failed to deploy hyperfoil server", e);
            }
            terminate.complete(null);
            return null;
        }));
        if (factory.config.mtls) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            mtlsCert = ssc.cert();
            mtlsKey = ssc.key();
            ssc.delete();
        } else {
            mtlsCert = null;
            mtlsKey = null;
        }
    }

    private HyperfoilInstances createInstances(OciLocation location, String privateSubnetId) throws Exception {
        Compute.Instance hyperfoilController = factory.compute.builder("hyperfoil-controller", location, privateSubnetId)
                .privateIp(HYPERFOIL_CONTROLLER_IP)
                .launch();
        try {
            computeInstances.add(hyperfoilController);
            List<Compute.Instance> agents = new ArrayList<>();
            for (int i = 0; i < factory.config.agentCount; i++) {
                Compute.Instance instance = factory.compute.builder(AGENT_INSTANCE_TYPE, location, privateSubnetId)
                        .privateIp(agentIp(i))
                        .launch();
                agents.add(instance);
                computeInstances.add(instance);
            }
            return new HyperfoilInstances(hyperfoilController, agents);
        } catch (Exception e) {
            try {
                terminateAndWait();
            } catch (Exception f) {
                e.addSuppressed(f);
            }
            throw e;
        }
    }

    private void terminateAndWait() {
        for (Compute.Instance computeInstance : computeInstances) {
            computeInstance.terminateAsync();
        }
        for (Compute.Instance computeInstance : computeInstances) {
            computeInstance.close();
        }
    }

    private void deploy() throws Exception {
        instances.controller.awaitStartup();

        SshFactory.Relay relay = this.relay.get();

        try (
                OutputListener.Write log = new OutputListener.Write(Files.newOutputStream(logDirectory.resolve("hyperfoil.log")));
                ClientSession controllerSession = factory.sshFactory.connect(instances.controller, HYPERFOIL_CONTROLLER_IP, relay)) {
            this.controllerSession = controllerSession;

            List<Callable<Void>> setupTasks = new ArrayList<>();

            setupTasks.add(() -> {
                SshUtil.run(controllerSession, "sudo yum install jdk-17-headless -y", log);
                ScpClientCreator.instance().createScpClient(controllerSession).upload(factory.config.location, REMOTE_HYPERFOIL_LOCATION, ScpClient.Option.Recursive, ScpClient.Option.PreserveAttributes);
                factory.sshFactory.deployPrivateKey(controllerSession);

                SshUtil.openFirewallPorts(controllerSession);
                return null;
            });

            for (int i = 0; i < instances.agents.size(); i++) {
                Compute.Instance agent = instances.agents.get(i);

                String agentIp = agentIp(i);
                setupTasks.add(() -> {
                    agent.awaitStartup();
                    try (ClientSession agentSession = factory.sshFactory.connect(agent, agentIp, relay)) {
                        SshUtil.openFirewallPorts(agentSession);
                        SshUtil.run(agentSession, "sudo yum install jdk-17-headless -y", log);
                        if (factory.config.agentAsyncProfiler) {
                            SshUtil.run(agentSession, "sudo sysctl kernel.perf_event_paranoid=1", log);
                            SshUtil.run(agentSession, "sudo sysctl kernel.kptr_restrict=0", log);
                            ScpClientCreator.instance().createScpClient(agentSession)
                                    .upload(factory.asyncProfilerConfiguration.path(), PROFILER_LOCATION);
                        }
                    }
                    return null;
                });
            }

            for (Future<?> f : factory.executor.invokeAll(setupTasks)) {
                f.get();
            }

            try (ChannelExec controllerCommand = controllerSession.createExecChannel(REMOTE_HYPERFOIL_LOCATION + "/bin/controller.sh -Djgroups.join_timeout=20000");
                 ResilientSshPortForwarder controllerPortForward = factory.resilientForwarderFactory.create(
                         () -> factory.sshFactory.connect(instances.controller, HYPERFOIL_CONTROLLER_IP, relay),
                         new SshdSocketAddress("localhost", 8090)
                 );
                 RestClient client = new RestClient(
                         factory.vertx,
                         controllerPortForward.address().getHostName(),
                         controllerPortForward.address().getPort(),
                         false, true, null)) {
                this.controllerPortForward.complete(controllerPortForward);

                SshUtil.forwardOutput(controllerCommand, log);
                controllerCommand.open().verify();

                while (true) {
                    try {
                        client.ping();
                        break;
                    } catch (RestClientException e) {
                        if (!(e.getCause() instanceof HttpClosedException hce) || !hce.getMessage().equals("Connection was closed")) {
                            throw e;
                        }
                    }
                    if (!controllerCommand.isOpen()) {
                        throw new IllegalStateException("Controller exec channel closed, did the controller die?");
                    }
                    LOG.info("Connecting to hyperfoil controller forwarded at {}", controllerPortForward.address());
                    TimeUnit.SECONDS.sleep(1);
                }

                this.client.complete(client);

                //noinspection InfiniteLoopStatement
                while (true) {
                    synchronized (this) {
                        wait(); // wait for interrupt
                    }
                }
            } finally {
                this.controllerSession = null;
                LOG.info("Closing benchmark client");

                if (factory.config.agentAsyncProfiler) {
                    for (int i = 0; i < instances.agents.size(); i++) {
                        try (ClientSession agentSession = factory.sshFactory.connect(null, agentIp(i), relay)) {
                            LOG.info("Downloading agent {} profiler result", i);
                            for (String output : factory.asyncProfilerConfiguration.outputs()) {
                                ScpClientCreator.instance().createScpClient(agentSession)
                                        .download(output, logDirectory.resolve("agent" + i + "-" + output));
                            }
                        } catch (Exception e) {
                            LOG.error("Failed to download agent profiler results", e);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            this.client.completeExceptionally(t);
            this.relay.completeExceptionally(t);
            throw t;
        }
    }

    public void setRelay(SshFactory.Relay relay) {
        this.relay.complete(relay);
    }

    /**
     * Create a new benchmark closure to run benchmarks for a given request definition.
     *
     * @param outputDirectory The output directory for benchmark results
     * @param protocol        The HTTP protocol settings to use
     * @param body            The HTTP request
     * @return The benchmark closure
     */
    public FrameworkRun.BenchmarkClosure benchmarkClosure(Path outputDirectory, ProtocolSettings protocol, RequestDefinition.SampleRequestDefinition body) {
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
    
    private static byte[] pem(String type, byte[] der) {
        return ("-----BEGIN " + type + "-----\n" + Base64.getEncoder().encodeToString(der) + "\n-----END " + type + "-----").getBytes(StandardCharsets.UTF_8);
    }

    private String createCurlCommand(Protocol protocol, RequestDefinition definition, String socketUri, boolean verbose) throws CertificateEncodingException {
        StringBuilder builder = new StringBuilder("curl");
        builder.append(protocol == Protocol.HTTP1 ? " --http1.1" : " --http2");
        builder.append(" --insecure");
        if (mtlsCert != null) {
            builder.append(" --cert <(echo '");
            builder.append(Base64.getEncoder().encodeToString(pem("CERTIFICATE", mtlsCert.getEncoded())));
            builder.append("' | base64 -d)");
        }
        if (mtlsKey != null) {
            builder.append(" --key <(echo '");
            builder.append(Base64.getEncoder().encodeToString(pem("PRIVATE KEY", mtlsKey.getEncoded())));
            builder.append("' | base64 -d)");
        }
        builder.append(verbose ? " -v" : " --silent");
        builder.append(" -X ").append(definition.getMethod().name());
        builder.append(" --request-target '").append(definition.getUri()).append("'");
        builder.append(" -H 'host: ").append(definition.getHost()).append("'");
        if (definition.getRequestBody() != null) {
            builder.append(" -H 'content-type: ").append(definition.getRequestType()).append("'");
            builder.append(" -d '").append(definition.getRequestBody()).append("'");
        }
        builder.append(' ').append(socketUri);
        return builder.toString();
    }

    private void benchmark(Path outputDirectory, ProtocolSettings protocol, RequestDefinition.SampleRequestDefinition body, PhaseTracker.PhaseUpdater progress, boolean forPgo) throws Exception {
        BenchmarkPhase benchmarkPhase = forPgo ? BenchmarkPhase.PGO : BenchmarkPhase.BENCHMARKING;

        progress.update(benchmarkPhase);
        String ip = Infrastructure.SERVER_IP;
        int port = protocol.protocol() == Protocol.HTTP1 ? 8080 : 8443;
        io.hyperfoil.http.config.Protocol prot = protocol.protocol() == Protocol.HTTP1 ? io.hyperfoil.http.config.Protocol.HTTP : io.hyperfoil.http.config.Protocol.HTTPS;

        String socketUri = prot.scheme + "://" + ip + ":" + port;
        for (HyperfoilConfiguration.StatusRequest status : factory.config.status) {
            Infrastructure.retry(() -> {
                try (OutputListener.Write write = new OutputListener.Write(Files.newOutputStream(outputDirectory.resolve(status.getName() + ".http")))) {
                    SshUtil.run(controllerSession, createCurlCommand(protocol.protocol(), status, socketUri, true), write);
                }
                return null;
            }, controllerPortForward.get()::disconnect);
        }
        Infrastructure.retry(() -> {
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            try (OutputListener.Write write = new OutputListener.Write(resp)) {
                SshUtil.run(controllerSession, createCurlCommand(protocol.protocol(), body, socketUri, false), write);
            }
            boolean matches = switch (body.getResponseMatchingMode()) {
                case EQUAL -> Arrays.equals(resp.toByteArray(), body.getResponseBody().getBytes(StandardCharsets.UTF_8));
                case JSON -> factory.objectMapper.readTree(resp.toByteArray()).equals(factory.objectMapper.readTree(body.getResponseBody()));
                case REGEX -> Pattern.compile(body.getResponseBody()).matcher(resp.toString(StandardCharsets.UTF_8)).matches();
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
        Compute.ComputeConfiguration.InstanceType agentInstanceType = factory.compute.getInstanceType(AGENT_INSTANCE_TYPE);
        for (int i = 0; i < factory.config.agentCount; i++) {
            String extras = "-Dio.hyperfoil.cpu.watchdog.period=10000 -XX:+TieredCompilation -XX:TieredStopAtLevel=1 -XX:+UseZGC -Xmx" + ((int) (agentInstanceType.memoryInGb() * 0.8)) + "G";
            if (factory.config.agentAsyncProfiler) {
                extras += " -agentpath:" + PROFILER_LOCATION + "=" + factory.asyncProfilerConfiguration.args();
            }
            benchmark.addAgent("agent" + i, agentIp(i) + ":22", Map.of(
                    "threads", String.valueOf((int) agentInstanceType.ocpus() - 1),
                    "extras", extras
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

        Client client = this.client.get();
        Client.BenchmarkRef benchmarkRef = client.register(builtBenchmark, null);
        Client.RunRef runRef = benchmarkRef.start("run", Map.of());
        long startTime = System.nanoTime();
        String lastPhase = null;
        while (true) {
            RequestStatisticsResponse recentStats = Infrastructure.retry(runRef::statsRecent, controllerPortForward.get()::disconnect);
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
        }, controllerPortForward.get()::disconnect);
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
                benchmarkFailures.add("No responses in phase " + stats.phase);
                invalidatesBenchmark = true;
            }
        }

        if (!forPgo || !benchmarkFailures.isEmpty()) {
            LOG.info("Downloading agent logs…");
            try {
                for (String agent : Infrastructure.retry(client::agents, controllerPortForward.get()::disconnect)) {
                    Infrastructure.retry(() -> client.downloadLog(agent, null, 0, outputDirectory.resolve(agent.replaceAll("[^0-9a-zA-Z]", "") + ".log").toFile()), controllerPortForward.get()::disconnect);
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

    private static HttpRequestStepBuilder prepareScenario(RequestDefinition sampleRequest, String ip, int port, ScenarioBuilder warmup) {
        return warmup.initialSequence("test")
                .step(HttpStepCatalog.class)
                .httpRequest(sampleRequest.getMethod())
                .authority(ip + ":" + port)
                .path(sampleRequest.getUri())
                // MUST be lowercase for HTTP/2
                .headers().header("content-type", sampleRequest.getRequestType()).endHeaders()
                .body(sampleRequest.getRequestBody() == null ? null : new ConstantBytesGenerator(sampleRequest.getRequestBody().getBytes(StandardCharsets.UTF_8)));
    }

    public CompletableFuture<?> terminateAsync() {
        worker.cancel(true);
        return terminate;
    }

    @Override
    public void close() throws Exception {
        terminateAsync();
        terminate.get();
    }

    private static String agentIp(int i) {
        return HYPERFOIL_AGENT_PREFIX + (i + 1);
    }

    @Singleton
    static final class Factory {
        private final Compute compute;
        private final SshFactory sshFactory;
        private final ExecutorService executor;
        private final HyperfoilConfiguration config;
        private final ObjectMapper objectMapper;
        private final ResilientSshPortForwarder.Factory resilientForwarderFactory;
        private final Vertx vertx;
        private final AsyncProfilerConfiguration asyncProfilerConfiguration;

        Factory(Compute compute, SshFactory sshFactory, @Named(TaskExecutors.IO) ExecutorService executor, HyperfoilConfiguration config, ObjectMapper objectMapper, ResilientSshPortForwarder.Factory resilientForwarderFactory, AsyncProfilerConfiguration asyncProfilerConfiguration) {
            this.compute = compute;
            this.sshFactory = sshFactory;
            this.executor = executor;
            this.config = config;
            this.objectMapper = objectMapper;
            this.resilientForwarderFactory = resilientForwarderFactory;
            this.asyncProfilerConfiguration = asyncProfilerConfiguration;
            this.vertx = Vertx.vertx();

            objectMapper.registerSubtypes(HttpStats.class);
        }

        public HyperfoilRunner launch(Path outputDirectory, OciLocation location, String privateSubnetId) throws Exception {
            return new HyperfoilRunner(this, outputDirectory, location, privateSubnetId);
        }
    }

    /**
     * @param location           Location of the hyperfoil directory
     * @param agentCount         Number of agents to create
     * @param warmupDuration     Duration of the warmup run
     * @param benchmarkDuration  Duration of each main benchmark run
     * @param pgoDuration        Duration of the PGO run
     * @param sessionLimitFactor Factor of the hyperfoil session limit. If a particular run is 1000 ops/s, and this
     *                           factor is 2, the maximum number of hyperfoil sessions is 2000.
     * @param status             Optional requests to do before the benchmark run to get metadata from the SUT. This
     *                           metadata will be saved in the result directory. Can be used to verify that the SUT has
     *                           started with the correct settings.
     */
    @ConfigurationProperties("hyperfoil")
    public record HyperfoilConfiguration(
            Path location,
            int agentCount,
            Duration warmupDuration,
            Duration benchmarkDuration,
            Duration pgoDuration,
            double sessionLimitFactor,
            List<StatusRequest> status,
            boolean mtls,
            boolean agentAsyncProfiler
    ) {
        @EachProperty(value = "status", list = true)
        interface StatusRequest extends RequestDefinition, io.micronaut.core.naming.Named {
        }
    }

    private record HyperfoilInstances(
            Compute.Instance controller,
            List<Compute.Instance> agents
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StatsAll(
            Info info,
            List<SlaFailure> failures,
            List<Stats> stats
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Info(
                List<Error> errors
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            private record Error(
                    String agent,
                    String msg
            ) {

            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record SlaFailure(
                String phase,
                String message
        ) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Stats(
                String phase,
                Total total
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            private record Total(StatisticsSummary summary) {
            }
        }
    }

    private record Metadata(HyperfoilConfiguration hyperfoilConfiguration) {}
}
