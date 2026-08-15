package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.cmd.TokenRoutingOutputListener;
import io.micronaut.benchmark.loadgen.oci.resource.NixosCacheResource;
import io.micronaut.benchmark.loadgen.oci.resource.PhasedResource;
import io.micronaut.benchmark.loadgen.oci.resource.ResourceContext;
import io.micronaut.core.annotation.Indexed;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Infrastructure for hyperfoil benchmarks, with a single server-under-test, and a hyperfoil cluster sending HTTP
 * requests to it.
 */
@Singleton
public final class Infrastructure extends AbstractInfrastructure {
    private static final Logger LOG = LoggerFactory.getLogger(Infrastructure.class);

    static final String SERVER_IP = "10.0.0.2";
    static final String BENCHMARK_SERVER_INSTANCE_TYPE = "benchmark-server";
    public static final String BENCHMARK_BOOTSTRAP = "benchmark-bootstrap";
    private static final Duration CONSOLE_STOP_TIMEOUT = Duration.ofMinutes(5);

    private final Factory factory;

    Compute.Instance benchmarkServer;
    private final HyperfoilRunner hyperfoilRunner;
    private final PhasedResource.PhaseLock hyperfoilLock;
    private final Map<String, NixosCacheResource> nixosConfigurations;
    private final Map<String, PhasedResource.PhaseLock> nixosConfigurationLocks;
    private final OutputListener.Write benchmarkServerLog;
    private final TokenRoutingOutputListener benchmarkServerConsoleHistory;
    private boolean started;
    private boolean stopped;

    private Infrastructure(Factory factory, OciLocation location, Path logDirectory, Set<String> configurations) throws Exception {
        super(factory.baseFactory, location, logDirectory);
        this.factory = factory;
        Files.createDirectories(logDirectory);
        benchmarkServerLog = new OutputListener.Write(Files.newOutputStream(logDirectory.resolve("benchmark-server.log")));
        benchmarkServerConsoleHistory = new TokenRoutingOutputListener(benchmarkServerLog);

        hyperfoilRunner = factory.hyperfoilRunnerFactory.create(logDirectory, this);
        hyperfoilLock = hyperfoilRunner.require();
        BenchmarkMetadata.InstanceType instanceType = factory.compute.getInstanceType(BENCHMARK_SERVER_INSTANCE_TYPE);
        Map<String, NixosCacheResource> resources = new LinkedHashMap<>();
        for (String configuration : Objects.requireNonNull(configurations, "configurations")) {
            resources.putIfAbsent(configuration, factory.compute.cacheResource(instanceType, configuration));
        }
        resources.computeIfAbsent(BENCHMARK_BOOTSTRAP, name -> {
            try {
                return factory.compute.cacheResource(instanceType, name);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to prepare benchmark bootstrap", e);
            }
        });
        nixosConfigurations = Map.copyOf(resources);
        nixosConfigurationLocks = nixosConfigurations.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> PhasedResource.PhaseLock.combine(entry.getValue().require())
                ));
        nixosConfigurations.values().forEach(resource -> launch(resource, resource::manage));
    }

    private void start(PhaseTracker.PhaseUpdater progress) throws Exception {
        setupBase(progress);

        launch(hyperfoilRunner, hyperfoilRunner::manage);

        List<NixosCacheResource> prefetchResources = prefetchResources();
        Compute.Launch benchmarkServerLaunch = computeBuilder(BENCHMARK_SERVER_INSTANCE_TYPE)
                .privateIp(SERVER_IP)
                .nixosConfiguration(nixosConfigurations.get(BENCHMARK_BOOTSTRAP))
                .consoleHistory(benchmarkServerConsoleHistory);
        if (!prefetchResources.isEmpty()) {
            benchmarkServerLaunch.systemdCredential(
                    Path.of("/etc/credstore/benchmark-prefetch/script"),
                    Nix.prefetch(prefetchResources).getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
        }
        benchmarkServer = benchmarkServerLaunch.launch();

        for (Attachment attachment : factory.attachments) {
            attachment.setUp(this);
        }

        benchmarkServer.awaitStartup();
        try {
            PhasedResource.PhaseLock.awaitAll(lifecycleLocks);
        } finally {
            stopPrefetch();
        }

        started = true;
    }

    private List<NixosCacheResource> prefetchResources() throws Exception {
        List<NixosCacheResource> resources = new java.util.ArrayList<>();
        for (Map.Entry<String, NixosCacheResource> entry : nixosConfigurations.entrySet()) {
            if (!entry.getKey().equals(BENCHMARK_BOOTSTRAP)) {
                nixosConfigurationLocks.get(entry.getKey()).await();
                resources.add(entry.getValue());
            }
        }
        return List.copyOf(resources);
    }

    private void stopPrefetch() throws Exception {
        if (benchmarkServer != null) {
            try (CommandRunner client = benchmarkServer.connectSsh()) {
                client.runAndCheck("systemctl stop -- " + "benchmark-prefetch.service", benchmarkServerLog);
            }
        }
    }

    @SuppressWarnings("EmptyTryBlock")
    @Override
    public void close() throws Exception {
        stopped = true;
        // Safely close everything
        try (hyperfoilLock;
             PhasedResource.PhaseLock _ = PhasedResource.PhaseLock.combine(nixosConfigurationLocks.values().stream().toList());
             OutputListener.Write _ = benchmarkServerLog;
             Compute.Instance _ = benchmarkServer;
             AutoCloseable _ = super::close
        ) {
        }
    }

    /**
     * Run the given benchmark on this infrastructure. This method is synchronized, so if multiple benchmarks call
     * this simultaneously, the infrastructure will run them one-by-one.
     *
     * @param outputDirectory The benchmark output directory
     * @param run             The framework configuration to run
     * @param loadVariant     The benchmark load (HTTP protocol settings, request info)
     * @param progress        Progress updater
     */
    public synchronized void run(Path outputDirectory, FrameworkRun run, LoadVariant loadVariant, PhaseTracker.PhaseUpdater progress) throws Exception {
        if (stopped) {
            throw new InterruptedException("Already stopped");
        }
        try {
            if (!started) {
                start(progress);
            }

            Files.createDirectories(outputDirectory);
            try (OutputListener.Write log = new OutputListener.Write(
                    Files.newOutputStream(outputDirectory.resolve("server.log")))) {
                boolean benchmarkLogActive = false;
                try {
                    switchOutput(marker("START"), log);
                    benchmarkLogActive = true;
                    activate(log, configuration(run), progress);
                    retry(() -> {
                        try {
                            run0(log, outputDirectory, run, loadVariant, progress);
                        } catch (Exception e) {
                            LOG.error("Benchmark run failed, may retry", e);
                            throw e;
                        }
                        return null;
                    });
                } finally {
                    if (benchmarkLogActive) {
                        try {
                            switchOutput(marker("STOP"), benchmarkServerLog);
                        } catch (Exception e) {
                            LOG.warn("Failed to switch benchmark server output back to the central log", e);
                        }
                    }
                    try {
                        activate(log, BENCHMARK_BOOTSTRAP, progress);
                    } catch (Exception e) {
                        stopped = true;
                        LOG.warn("Failed to restore benchmark server bootstrap configuration", e);
                    }
                }
            }
        } catch (Exception e) {
            // prevent reuse
            stopped = true;
            throw e;
        }
    }

    private String configuration(FrameworkRun run) {
        String configuration = run.nixosConfiguration();
        return configuration == null ? BENCHMARK_BOOTSTRAP : configuration;
    }

    private void switchOutput(String marker, OutputListener target) {
        TokenRoutingOutputListener.Switch outputSwitch = benchmarkServerConsoleHistory.switchOn(marker, target);
        try {
            emitConsoleMarker(marker);
        } catch (Exception e) {
            LOG.debug("Marker command failed; waiting for console output", e);
        }
        try {
            outputSwitch.await(CONSOLE_STOP_TIMEOUT);
        } catch (Exception e) {
            outputSwitch.cancel(benchmarkServerLog);
            throw e;
        }
    }

    private static String marker(String state) {
        return "MICRONAUT_BENCHMARK_CONSOLE_" + state + "_" + java.util.UUID.randomUUID();
    }

    private void emitConsoleMarker(String marker) throws Exception {
        String quotedMarker = "'" + marker.replace("'", "'\"'\"'") + "'";
        try (CommandRunner client = benchmarkServer.connectSsh()) {
            client.runAndCheck("printf '%s\\n' " + quotedMarker + " | systemd-cat");
        }
    }

    private void activate(OutputListener.Write log, String configuration, PhaseTracker.PhaseUpdater progress) throws Exception {
        stopPrefetch();
        NixosCacheResource resource = nixosConfigurations.get(configuration);
        if (resource == null) {
            throw new IllegalArgumentException("NixOS configuration was not prepared: " + configuration);
        }
        PhasedResource.PhaseLock lock = nixosConfigurationLocks.get(configuration);
        if (lock == null) {
            throw new IllegalArgumentException("NixOS configuration lock was not prepared: " + configuration);
        }
        lock.await();
        progress.update(BenchmarkPhase.DEPLOYING_OS);
        log.println("----------------- NixOS deployment target: " + configuration);
        retry(() -> {
            try (CommandRunner client = benchmarkServer.connectSsh()) {
                client.runAndCheck(resource.activation(), log);
            }
            return null;
        });
    }

    private void run0(OutputListener.Write log, Path outputDirectory, FrameworkRun run, LoadVariant loadVariant,
                      PhaseTracker.PhaseUpdater progress) throws Exception {
        try (CommandRunner benchmarkServerClient = benchmarkServer.connectSsh()) {
            // special PhaseUpdater that logs the current benchmark phase for reference.
            progress = new PhaseTracker.DelegatePhaseUpdater(progress) {
                String lastDisplay = null;

                @Override
                public void update(BenchmarkPhase phase, double percent, @Nullable String displayProgress) {
                    if (!Objects.equals(displayProgress, lastDisplay)) {
                        log.println("----------------- Benchmark progress changed to: " + displayProgress);
                        lastDisplay = displayProgress;
                    }
                    super.update(phase, percent, displayProgress);
                }
            };

            PhaseTracker.PhaseUpdater finalProgress = progress;
            factory.sutMonitor.monitorAndRun(
                    benchmarkServerClient,
                    outputDirectory,
                    () -> {
                        run.setupAndRun(
                                benchmarkServerClient,
                                outputDirectory,
                                log,
                                hyperfoilRunner.benchmarkClosure(outputDirectory, loadVariant.protocol(), loadVariant.definition()),
                                finalProgress);
                        return null;
                    }
            );
        }
    }

    @Indexed(Attachment.class)
    public interface Attachment {
        void setUp(Infrastructure infrastructure) throws Exception;
    }

    @Singleton
    public record Factory(
            AbstractInfrastructure.Factory baseFactory,
            ResourceContext context,
            Compute compute,
            HyperfoilRunner.Factory hyperfoilRunnerFactory,
            SutMonitor sutMonitor,
            List<Attachment> attachments
    ) {
        Infrastructure create(OciLocation location, Path logDirectory, Set<String> nixosConfigurations) throws Exception {
            return new Infrastructure(this, location, logDirectory, nixosConfigurations);
        }
    }
}
