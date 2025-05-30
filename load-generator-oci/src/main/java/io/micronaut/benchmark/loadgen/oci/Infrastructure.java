package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.resource.PhasedResource;
import io.micronaut.benchmark.loadgen.oci.resource.ResourceContext;
import io.micronaut.core.annotation.Indexed;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Infrastructure for hyperfoil benchmarks, with a single server-under-test, and a hyperfoil cluster sending HTTP
 * requests to it.
 */
@Singleton
public final class Infrastructure extends AbstractInfrastructure {
    private static final Logger LOG = LoggerFactory.getLogger(Infrastructure.class);

    static final String SERVER_IP = "10.0.0.2";
    static final String BENCHMARK_SERVER_INSTANCE_TYPE = "benchmark-server";

    private final Factory factory;

    Compute.Instance benchmarkServer;
    private HyperfoilRunner hyperfoilRunner;

    private boolean started;
    private boolean stopped;

    private Infrastructure(Factory factory, OciLocation location, Path logDirectory) {
        super(location, logDirectory, factory.context, factory.compute);
        this.factory = factory;
    }

    private void start(PhaseTracker.PhaseUpdater progress) throws Exception {
        setupBase(progress);

        benchmarkServer = computeBuilder(BENCHMARK_SERVER_INSTANCE_TYPE)
                .privateIp(SERVER_IP)
                .launch();

        for (Attachment attachment : factory.attachments) {
            attachment.setUp(this);
        }

        hyperfoilRunner = factory.hyperfoilRunnerFactory.launch(logDirectory, this);

        benchmarkServer.awaitStartup();

        try (ClientSession benchmarkServerClient = benchmarkServer.connectSsh();
             OutputListener.Write log = new OutputListener.Write(Files.newOutputStream(logDirectory.resolve("update.log")))) {

            progress.update(BenchmarkPhase.DEPLOYING_OS);
            LOG.info("Updating benchmark server");
            SshUtil.openFirewallPorts(benchmarkServerClient, log);
            // this takes too long
            //SshUtil.run(benchmarkServerClient, "sudo yum update -y", log);
        }

        PhasedResource.PhaseLock.awaitAll(lifecycleLocks);

        started = true;
    }

    @Override
    public void close() throws Exception {
        stopped = true;

        // terminate asynchronously. we will wait for termination in close()
        if (hyperfoilRunner != null) {
            hyperfoilRunner.terminateAsync();
        }
        if (benchmarkServer != null) {
            benchmarkServer.terminateAsync();
        }
        if (hyperfoilRunner != null) {
            hyperfoilRunner.close();
        }
        if (benchmarkServer != null) {
            benchmarkServer.close();
        }

        super.close();
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

            try {
                Files.createDirectories(outputDirectory);
            } catch (FileAlreadyExistsException ignored) {
            }

            retry(() -> {
                try {
                    run0(outputDirectory, run, loadVariant, progress);
                } catch (Exception e) {
                    LOG.error("Benchmark run failed, may retry", e);
                    throw e;
                }
                return null;
            });
        } catch (Exception e) {
            // prevent reuse
            stopped = true;
            throw e;
        }
    }

    private void run0(Path outputDirectory, FrameworkRun run, LoadVariant loadVariant, PhaseTracker.PhaseUpdater progress) throws Exception {
        try (ClientSession benchmarkServerClient = benchmarkServer.connectSsh();
             OutputListener.Write log = new OutputListener.Write(Files.newOutputStream(outputDirectory.resolve("server.log")))) {
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
            ResourceContext context,
            Compute compute,
            HyperfoilRunner.Factory hyperfoilRunnerFactory,
            SutMonitor sutMonitor,
            List<Attachment> attachments
    ) {
        Infrastructure create(OciLocation location, Path logDirectory) {
            return new Infrastructure(this, location, logDirectory);
        }
    }
}
