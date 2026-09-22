package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.api.Nix;
import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.cmd.TokenRoutingOutputListener;
import io.micronaut.benchmark.loadgen.oci.resource.NixosCacheResource;
import io.micronaut.benchmark.loadgen.oci.resource.PhasedResource;
import io.micronaut.core.annotation.Indexed;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One exclusively used environment. Its lifetime is independent of experiment submissions.
 */
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
    private final NixosCacheResource bootstrap;
    private final Map<Path, NixosCacheResource> publications = new HashMap<>();
    private final OutputListener.Write benchmarkServerLog;
    private final TokenRoutingOutputListener benchmarkServerConsoleHistory;
    private boolean started;
    private boolean usable = true;

    private Infrastructure(Factory factory, OciLocation location, Path logDirectory) throws Exception {
        super(factory.baseFactory, location, logDirectory);
        this.factory = factory;
        Files.createDirectories(logDirectory);
        benchmarkServerLog = new OutputListener.Write(Files.newOutputStream(logDirectory.resolve("benchmark-server.log")));
        benchmarkServerConsoleHistory = new TokenRoutingOutputListener(benchmarkServerLog);
        hyperfoilRunner = factory.hyperfoilRunnerFactory.create(logDirectory, this);
        hyperfoilLock = hyperfoilRunner.require();
        bootstrap = factory.compute.cacheResource(factory.compute.getInstanceType(BENCHMARK_SERVER_INSTANCE_TYPE), BENCHMARK_BOOTSTRAP);
    }

    public void start(PhaseTracker.PhaseUpdater progress) throws Exception {
        if (started) {
            return;
        }
        launch(bootstrap, bootstrap::manage);
        bootstrap.signalPublication();
        setupBase(progress);
        launch(hyperfoilRunner, hyperfoilRunner::manage);
        bootstrap.awaitPublished();
        benchmarkServer = computeBuilder(BENCHMARK_SERVER_INSTANCE_TYPE).privateIp(SERVER_IP)
                .nixosConfiguration(bootstrap).consoleHistory(benchmarkServerConsoleHistory).launch();
        for (Attachment attachment : factory.attachments) attachment.setUp(this);
        benchmarkServer.awaitStartup();
        PhasedResource.PhaseLock.awaitAll(lifecycleLocks);
        started = true;
    }

    public boolean usable() {
        return usable;
    }

    @Override
    public void close() throws Exception {
        usable = false;
        bootstrap.cancelPublicationWait();
        try (AutoCloseable base = super::close;
             AutoCloseable server = benchmarkServer;
             var log = benchmarkServerLog;
             var lock = hyperfoilLock) {
        }
    }

    private NixCacheAccess publish(Path system) throws Exception {
        NixosCacheResource publication = publications.get(system);
        if (publication == null) {
            publication = factory.compute.outputCache(system);
            publication.signalPublication();
            // Publication belongs to the experiment thread so cancellation stops the build/copy too.
            publication.manage();
            publications.put(system, publication);
        }
        return publication.awaitPublished();
    }

    public void run(Path directory, PreparedExperiment experiment, PhaseTracker.PhaseUpdater progress) throws Exception {
        if (!usable) {
            throw new IllegalStateException("Environment requires recreation");
        }
        progress.update(BenchmarkPhase.PUBLISHING_CLOSURE);
        NixCacheAccess cache = publish(experiment.system());
        try (OutputListener.Write log = new OutputListener.Write(Files.newOutputStream(directory.resolve("server.log")))) {
            Exception failure = null;
            boolean cleared = false;
            try {
                switchOutput(marker("START"), log);
                progress.update(BenchmarkPhase.ACTIVATING_CONFIGURATION);
                activate(cache, log);
                try (CommandRunner client = benchmarkServer.connectSsh()) {
                    try (var information = Files.newOutputStream(directory.resolve("machine-info.txt"))) {
                        for (String file : List.of("/proc/version", "/proc/cpuinfo", "/proc/meminfo")) {
                            information.write((file + "\n").getBytes(StandardCharsets.UTF_8));
                            client.runAndCheck("cat -- " + Nix.shellQuote(file), new OutputListener.Write(information));
                        }
                    }
                    ArtifactCollector.clear(client, experiment.artifacts(), log);
                    cleared = true;
                    progress.update(BenchmarkPhase.STARTING_SERVER);
                    client.runAndCheck("systemctl restart -- sut.service", log);
                    factory.sutMonitor.monitorAndRun(client, directory, () -> {
                        hyperfoilRunner.benchmark(directory, directory.resolve("hyperfoil.yaml"), progress);
                        return null;
                    });
                }
            } catch (Exception e) {
                failure = e;
                if (e instanceof EnvironmentInvalidException) {
                    usable = false;
                }
            } finally {
                boolean interrupted = Thread.interrupted();
                try {
                    if (cleared) {
                        try (CommandRunner client = benchmarkServer.connectSsh()) {
                            try {
                                client.runAndCheck("systemctl stop -- sut.service", log);
                            } catch (Exception e) {
                                failure = combine(failure, e);
                            }
                            progress.update(BenchmarkPhase.COLLECTING_ARTIFACTS);
                            try {
                                ArtifactCollector.collect(client, directory, experiment.artifacts(), log);
                            } catch (Exception e) {
                                failure = combine(failure, e);
                            }
                        } catch (Exception e) {
                            failure = combine(failure, e);
                        }
                    }
                    try {
                        progress.update(BenchmarkPhase.RESTORING_BOOTSTRAP);
                        activate(bootstrap.awaitPublished(), log);
                    } catch (Exception e) {
                        usable = false;
                        failure = combine(failure, e);
                    }
                    try {
                        switchOutput(marker("STOP"), benchmarkServerLog);
                    } catch (Exception e) {
                        LOG.warn("Could not finish console log routing", e);
                    }
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            if (failure != null) {
                if (!usable) {
                    throw new EnvironmentInvalidException("Infrastructure is unusable", failure);
                }
                throw failure;
            }
        }
    }

    private static Exception combine(Exception original, Exception next) {
        if (original == null) {
            return next;
        }
        original.addSuppressed(next);
        return original;
    }

    private void activate(NixCacheAccess cache, OutputListener log) throws Exception {
        retry(() -> {
            try (CommandRunner client = benchmarkServer.connectSsh()) {
                client.runAndCheck(Nix.activate(cache.readUri(), cache.defaultOutput()), log);
            }
            return null;
        });
    }

    private void switchOutput(String marker, OutputListener target) throws Exception {
        var change = benchmarkServerConsoleHistory.switchOn(marker, target);
        try {
            try (CommandRunner client = benchmarkServer.connectSsh()) {
                client.runAndCheck("printf '%s\\n' " + Nix.shellQuote(marker) + " | systemd-cat");
            }
            change.await(CONSOLE_STOP_TIMEOUT);
        } catch (Exception e) {
            change.cancel(benchmarkServerLog);
            throw e;
        }
    }

    private static String marker(String state) {
        return "MICRONAUT_BENCHMARK_CONSOLE_" + state + "_" + UUID.randomUUID();
    }
    @Indexed(Attachment.class)
    public interface Attachment {
        String name();
        void setUp(Infrastructure infrastructure) throws Exception;
    }
    @Singleton
    public record Factory(AbstractInfrastructure.Factory baseFactory, Compute compute,
                          HyperfoilRunner.Factory hyperfoilRunnerFactory, SutMonitor sutMonitor,
                          List<Attachment> attachments) {
        Infrastructure create(OciLocation location, Path logDirectory) throws Exception {
            return new Infrastructure(this, location, logDirectory);
        }
    }
}
