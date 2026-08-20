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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
    private final List<FrameworkRun.NixosConfiguration> configurations;
    private final OutputListener.Write benchmarkServerLog;
    private final TokenRoutingOutputListener benchmarkServerConsoleHistory;
    private boolean started;
    private volatile boolean stopped;
    private volatile Thread publicationThread;

    private Infrastructure(Factory factory, OciLocation location, Path logDirectory, List<FrameworkRun.NixosConfiguration> configurations) throws Exception {
        super(factory.baseFactory, location, logDirectory);
        this.factory = factory;
        this.configurations = List.copyOf(Objects.requireNonNull(configurations, "configurations"));
        Files.createDirectories(logDirectory);
        benchmarkServerLog = new OutputListener.Write(Files.newOutputStream(logDirectory.resolve("benchmark-server.log")));
        benchmarkServerConsoleHistory = new TokenRoutingOutputListener(benchmarkServerLog);

        hyperfoilRunner = factory.hyperfoilRunnerFactory.create(logDirectory, this);
        hyperfoilLock = hyperfoilRunner.require();
        BenchmarkMetadata.InstanceType instanceType = factory.compute.getInstanceType(BENCHMARK_SERVER_INSTANCE_TYPE);
        List<FrameworkRun.NixosConfiguration> declaredConfigurations = new ArrayList<>(this.configurations);
        declaredConfigurations.add(new FrameworkRun.NixosConfiguration(BENCHMARK_BOOTSTRAP, false));
        Map<String, FrameworkRun.NixosConfiguration> normalizedConfigurations = normalizeConfigurations(declaredConfigurations);
        Map<String, NixosCacheResource> resources = new LinkedHashMap<>();
        for (FrameworkRun.NixosConfiguration configuration : normalizedConfigurations.values()) {
            resources.put(configuration.name(), factory.compute.cacheResource(instanceType, configuration));
        }
        nixosConfigurations = Collections.unmodifiableMap(resources);
        nixosConfigurationLocks = nixosConfigurations.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> PhasedResource.PhaseLock.combine(entry.getValue().require())
                ));
        nixosConfigurations.values().forEach(resource -> launch(resource, resource::manage));
    }

    private static Map<String, FrameworkRun.NixosConfiguration> normalizeConfigurations(Iterable<FrameworkRun.NixosConfiguration> configurations) {
        Map<String, FrameworkRun.NixosConfiguration> normalized = new LinkedHashMap<>();
        for (FrameworkRun.NixosConfiguration configuration : configurations) {
            FrameworkRun.NixosConfiguration previous = normalized.putIfAbsent(configuration.name(), configuration);
            if (previous != null && previous.dynamicPgo() != configuration.dynamicPgo()) {
                throw new IllegalArgumentException("Conflicting NixOS configuration modes for " + configuration.name());
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    private void start(PhaseTracker.PhaseUpdater progress) throws Exception {
        NixosCacheResource bootstrap = nixosConfigurations.get(BENCHMARK_BOOTSTRAP);
        bootstrap.signalPublication();
        publicationThread = Thread.ofVirtual()
                .name("publish-nixos-configurations")
                .start(this::sequencePublications);

        setupBase(progress);
        if (stopped) {
            throw new InterruptedException("Already stopped");
        }

        launch(hyperfoilRunner, hyperfoilRunner::manage);

        Compute.Launch benchmarkServerLaunch = computeBuilder(BENCHMARK_SERVER_INSTANCE_TYPE)
                .privateIp(SERVER_IP)
                .nixosConfiguration(nixosConfigurations.get(BENCHMARK_BOOTSTRAP))
                .consoleHistory(benchmarkServerConsoleHistory);
        bootstrap.awaitPublished();
        benchmarkServer = benchmarkServerLaunch.launch();

        for (Attachment attachment : factory.attachments) {
            attachment.setUp(this);
        }

        benchmarkServer.awaitStartup();
        PhasedResource.PhaseLock.awaitAll(lifecycleLocks);

        started = true;
    }

    private void sequencePublications() {
        NixosCacheResource bootstrap = nixosConfigurations.get(BENCHMARK_BOOTSTRAP);
        try {
            bootstrap.awaitPublished();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            LOG.warn("Failed to publish NixOS configuration {}", BENCHMARK_BOOTSTRAP, e);
            return;
        }

        for (Map.Entry<String, NixosCacheResource> entry : nixosConfigurations.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(BENCHMARK_BOOTSTRAP) && !entry.getValue().dynamicPgo())
                .toList()) {
            if (stopped || Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                entry.getValue().signalPublication();
                entry.getValue().awaitPublished();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOG.warn("Failed to publish NixOS configuration {}", entry.getKey(), e);
            }
        }
    }

    @SuppressWarnings("EmptyTryBlock")
    @Override
    public void close() throws Exception {
        stopped = true;
        Thread publisher = publicationThread;
        if (publisher != null) {
            publisher.interrupt();
        }
        nixosConfigurations.values().forEach(NixosCacheResource::cancelPublicationWait);
        try (AutoCloseable _ = super::close;
             AutoCloseable _ = benchmarkServer;
             benchmarkServerLog;
             AutoCloseable _ = PhasedResource.PhaseLock.combine(nixosConfigurationLocks.values().stream().toList());
             hyperfoilLock
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
        return run.nixosConfigurations().getFirst().name();
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

    private NixCacheAccess cacheAccess(String configuration) throws Exception {
        NixosCacheResource resource = nixosConfigurations.get(configuration);
        if (resource == null) {
            throw new IllegalArgumentException("NixOS configuration was not prepared: " + configuration);
        }
        return resource.dynamicPgo() ? resource.awaitAvailable() : resource.awaitPublished();
    }

    private void activate(OutputListener.Write log, FrameworkRun.Activation request) throws Exception {
        NixCacheAccess cache = cacheAccess(request.configuration());
        activate(log, request, cache);
    }

    private void activate(OutputListener.Write log, FrameworkRun.Activation request, NixCacheAccess cache) throws Exception {
        request.progress().update(request.configuration().equals(BENCHMARK_BOOTSTRAP)
                ? BenchmarkPhase.RESTORING_BOOTSTRAP
                : BenchmarkPhase.ACTIVATING_CONFIGURATION);
        log.println("----------------- NixOS deployment target: " + request.configuration());
        retry(() -> {
            try (CommandRunner client = benchmarkServer.connectSsh()) {
                client.runAndCheck(Nix.activate(cache.readUri(), request.output()), log);
            }
            return null;
        });
    }

    private void activate(OutputListener.Write log, String configuration, PhaseTracker.PhaseUpdater progress) throws Exception {
        NixCacheAccess cache = cacheAccess(configuration);
        activate(log, new FrameworkRun.Activation(configuration, cache.requireDefaultOutput(), progress), cache);
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
                                 new FrameworkRun.ConfigurationActivator() {
                                     @Override
                                     public NixCacheAccess resolve(String configuration) throws Exception {
                                         return Infrastructure.this.cacheAccess(configuration);
                                     }

                                     @Override
                                     public void activate(FrameworkRun.Activation request) throws Exception {
                                         Infrastructure.this.activate(log, request);
                                     }
                                 },
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
        Infrastructure create(OciLocation location, Path logDirectory, List<FrameworkRun.NixosConfiguration> nixosConfigurations) throws Exception {
            return new Infrastructure(this, location, logDirectory, nixosConfigurations);
        }
    }
}
