package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Main runner for the benchmark suite.
 */
@Singleton
public final class SuiteRunner {
    private static final Logger LOG = LoggerFactory.getLogger(SuiteRunner.class);

    private final CompartmentCleaner compartmentCleaner;
    private final List<OciLocation> locations;
    private final Infrastructure.Factory infraFactory;
    private final LoadManager loadManager;
    private final List<FrameworkRunSet> frameworks;
    private final ExecutorService executor;
    private final SuiteConfiguration suiteConfiguration;
    private final ObjectMapper objectMapper;

    public SuiteRunner(CompartmentCleaner compartmentCleaner,
                       List<OciLocation> locations,
                       Infrastructure.Factory infraFactory,
                       LoadManager loadManager,
                       List<FrameworkRunSet> frameworks,
                       @Named(TaskExecutors.IO) ExecutorService executor,
                       SuiteConfiguration suiteConfiguration,
                       ObjectMapper objectMapper,
                       Compute compute) {
        this.compartmentCleaner = compartmentCleaner;
        this.locations = locations;
        this.infraFactory = infraFactory;
        this.loadManager = loadManager;
        this.frameworks = frameworks;
        this.executor = executor;
        this.suiteConfiguration = suiteConfiguration;
        this.objectMapper = objectMapper;
    }

    /**
     * Clean the benchmark compartment in all configured regions/ADs.
     */
    public void clean() {
        for (OciLocation location : locations) {
            compartmentCleaner.cleanCompartment(location, false);
        }
    }

    public void run() throws Exception {
        Path outputDir = Path.of("output");
        try {
            Files.createDirectories(outputDir);
        } catch (FileAlreadyExistsException ignored) {}
        clean();

        List<LoadVariant> loadVariants = loadManager.getLoadVariants();
        // all benchmark tasks (all FrameworkRuns * all LoadVariants * number of reps)
        List<Callable<Void>> allTasks = new ArrayList<>();
        // benchmark index
        List<BenchmarkParameters> index = new ArrayList<>();
        // for REUSE mode, the shared infrastructures for each repetition
        List<Infrastructure> sharedInfra = new ArrayList<>();
        PhaseTracker phaseTracker = new PhaseTracker(objectMapper, outputDir);
        Semaphore semaphore = new Semaphore(suiteConfiguration.maxConcurrentRuns);
        for (int repetition = 0; repetition < suiteConfiguration.repetitions; repetition++) {
            OciLocation location = locations.get(repetition % locations.size());
            Infrastructure repInfra;
            if (suiteConfiguration.infrastructureMode == InfrastructureMode.REUSE) {
                // create the shared infrastructure for this repetition
                repInfra = infraFactory.create(location, outputDir.resolve("infra-" + repetition));
                sharedInfra.add(repInfra);
            } else {
                repInfra = null;
            }
            // iterate over all runs, and fill the allTasks list
            for (FrameworkRunSet framework : frameworks) {
                for (FrameworkRun run : framework.getRuns()) {
                    if (!suiteConfiguration.enabledRunTypes.contains(run.type())) {
                        continue;
                    }
                    for (LoadVariant loadVariant : loadVariants) {
                        String name = run.name() + "-" + loadVariant.name() + "-" + repetition;
                        index.add(new BenchmarkParameters(name, run.type(), run.parameters(), loadVariant, repetition));
                        PhaseTracker.PhaseUpdater phaseUpdater = phaseTracker.updater(name);
                        phaseUpdater.update(BenchmarkPhase.BEFORE);
                        Path out = outputDir.resolve(name);
                        allTasks.add(() -> {
                            MdcTracker.withMdc(name, () -> {
                                // we could use a child compartment here, but compartments seem to be heavily throttled
                                try {
                                    if (suiteConfiguration.infrastructureMode == InfrastructureMode.REUSE) {
                                        assert repInfra != null;
                                        // run() is synchronized, so this will wait until the infra is available for
                                        // running this benchmark.
                                        repInfra.run(out, run, loadVariant, phaseUpdater);
                                        phaseUpdater.update(BenchmarkPhase.DONE);
                                    } else {
                                        semaphore.acquire();
                                        // create a new infra just for us.
                                        try (Infrastructure infra = infraFactory.create(location, out)) {
                                            infra.run(out, run, loadVariant, phaseUpdater);
                                            phaseUpdater.update(BenchmarkPhase.SHUTTING_DOWN);
                                        }
                                        phaseUpdater.update(BenchmarkPhase.DONE);
                                        semaphore.release();
                                    }
                                } catch (Exception e) {
                                    phaseUpdater.update(BenchmarkPhase.FAILED);
                                    Throwable root = e;
                                    while (root.getCause() != null) {
                                        root = root.getCause();
                                    }
                                    if (root instanceof InterruptedException) {
                                        LOG.info("Benchmark interrupted", e);
                                    } else {
                                        LOG.error("Failed to run benchmark", e);
                                    }
                                    executor.shutdownNow();
                                }
                                return null;
                            });
                            return null;
                        });
                    }
                }
            }
        }
        Future<?> progressTask = executor.submit(() -> {
            try {
                phaseTracker.trackLoop();
            } catch (IOException e) {
                LOG.error("Error in phase tracker", e);
            }
        });
        Collections.shuffle(allTasks);
        LOG.info("There are {} benchmarks to run", allTasks.size());
        Path newIndex = outputDir.resolve("index.new.json");
        objectMapper.writeValue(newIndex.toFile(), index);
        // run allTasks and wait for them to finish.
        try {
            List<Future<Void>> futures = executor.invokeAll(allTasks);
            for (Future<Void> future : futures) {
                // any remaining errors
                future.get();
            }
        } finally {
            for (Infrastructure infrastructure : sharedInfra) {
                try {
                    infrastructure.close();
                } catch (Exception e) {
                    LOG.error("Failed to close shared infrastructure", e);
                }
            }
            for (OciLocation location : locations) {
                compartmentCleaner.cleanCompartment(location, false);
            }
        }
        progressTask.cancel(true);
        Files.move(newIndex, outputDir.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
        LOG.info("All benchmarks complete");
        System.exit(0);
    }

    /**
     * Configuration for the benchmark suite.
     *
     * @param enabledRunTypes    {@link FrameworkRun#type()} run types to enable in this suite
     * @param repetitions        Number of repetitions for each run. If you define multiple {@link OciLocation}s, each
     *                           repetition will run on a different location, if possible
     * @param maxConcurrentRuns  Maximum number of concurrent runs, to avoid running into resource limits (only for
     *                           {@link InfrastructureMode#INFRASTRUCTURE_PER_RUN})
     * @param infrastructureMode How infrastructure should be reused between runs
     */
    @ConfigurationProperties("suite")
    public record SuiteConfiguration(
            List<String> enabledRunTypes,
            int repetitions,
            int maxConcurrentRuns,
            InfrastructureMode infrastructureMode
    ) {
    }

    public enum InfrastructureMode {
        /**
         * Set up a new infrastructure for each run. Very parallelizable, somewhat wasteful (infra setup takes time),
         * more prone to infra differences between runs.
         */
        INFRASTRUCTURE_PER_RUN,
        /**
         * Use one infrastructure per repetition and run every framework config on it. Only as parallel as
         * {@link SuiteConfiguration#repetitions}, but less prone to bias between runs.
         */
        REUSE
    }

    public record BenchmarkParameters(
            String name,
            String type,
            Object parameters,
            LoadVariant load,
            int repetition
    ) {
    }
}
