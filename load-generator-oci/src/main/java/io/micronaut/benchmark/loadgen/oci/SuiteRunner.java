package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

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
    private final List<FrameworkRun> runs;
    private final ExecutorService executor;
    private final SuiteConfiguration suiteConfiguration;
    private final ObjectMapper objectMapper;

    public SuiteRunner(CompartmentCleaner compartmentCleaner,
                       List<OciLocation> locations,
                       Infrastructure.Factory infraFactory,
                       LoadManager loadManager,
                       NixRunSet runSet,
                       @Named(TaskExecutors.IO) ExecutorService executor,
                       SuiteConfiguration suiteConfiguration,
                       ObjectMapper objectMapper,
                       Compute compute) {
        this.compartmentCleaner = compartmentCleaner;
        this.locations = locations;
        this.infraFactory = infraFactory;
        this.loadManager = loadManager;
        this.runs = runSet.getRuns().stream().map(FrameworkRun.class::cast).toList();
        this.executor = executor;
        this.suiteConfiguration = suiteConfiguration;
        this.objectMapper = objectMapper;
    }

    /**
     * Clean the benchmark compartment in all configured regions/ADs.
     */
    public void clean() throws Exception {
        compartmentCleaner.cleanCompartments(locations, false);
    }

    public void run() throws Exception {
        Path outputDir = Path.of("output");
        try {
            Files.createDirectories(outputDir);
        } catch (FileAlreadyExistsException ignored) {}
        clean();

        List<LoadVariant> loadVariants = loadManager.getLoadVariants();
        List<BenchmarkSpec> benchmarkSpecs = new ArrayList<>();
        List<BenchmarkParameters> index = new ArrayList<>();
        PhaseTracker phaseTracker = new PhaseTracker(objectMapper, outputDir);
        Semaphore semaphore = new Semaphore(suiteConfiguration.maxConcurrentRuns);
        for (int repetition = 0; repetition < suiteConfiguration.repetitions; repetition++) {
            OciLocation location = locations.get(repetition % locations.size());
            for (FrameworkRun run : runs) {
                for (LoadVariant loadVariant : loadVariants) {
                    String name = run.name() + "-" + loadVariant.name() + "-" + repetition;
                    index.add(new BenchmarkParameters(
                            name,
                            run.type(),
                             run.parameters(),
                             run.profiling(),
                             loadVariant,
                            repetition,
                            infraFactory.compute().getInstanceType(Infrastructure.BENCHMARK_SERVER_INSTANCE_TYPE)
                    ));
                    PhaseTracker.PhaseUpdater phaseUpdater = phaseTracker.updater(name);
                    phaseUpdater.update(BenchmarkPhase.QUEUED);
                    benchmarkSpecs.add(new BenchmarkSpec(repetition, location, run, loadVariant, name,
                            outputDir.resolve(name), phaseUpdater));
                }
            }
        }
        Collections.shuffle(benchmarkSpecs);

        Infrastructure[] sharedInfrastructure;
        if (suiteConfiguration.infrastructureMode == InfrastructureMode.REUSE) {
            sharedInfrastructure = new Infrastructure[suiteConfiguration.repetitions];
            for (int repetition = 0; repetition < suiteConfiguration.repetitions; repetition++) {
                List<FrameworkRun.NixosConfiguration> configurations = new ArrayList<>();
                for (BenchmarkSpec benchmarkSpec : benchmarkSpecs) {
                    if (benchmarkSpec.repetition() == repetition) {
                        configurations.addAll(benchmarkSpec.run().nixosConfigurations());
                    }
                }
                sharedInfrastructure[repetition] = infraFactory.create(
                        locations.get(repetition % locations.size()),
                        outputDir.resolve("infra-" + repetition),
                        configurations
                );
            }
        } else {
            sharedInfrastructure = null;
        }

        List<Callable<Void>> allTasks = benchmarkSpecs.stream()
                .<Callable<Void>>map(benchmarkSpec -> () -> {
                    MdcTracker.withMdc(benchmarkSpec.name(), () -> {
                        // we could use a child compartment here, but compartments seem to be heavily throttled
                        try {
                            if (suiteConfiguration.infrastructureMode == InfrastructureMode.REUSE) {
                                sharedInfrastructure[benchmarkSpec.repetition()].run(
                                        benchmarkSpec.output(), benchmarkSpec.run(), benchmarkSpec.loadVariant(), benchmarkSpec.phaseUpdater());
                                benchmarkSpec.phaseUpdater().update(BenchmarkPhase.DONE);
                            } else {
                                semaphore.acquire();
                                // create a new infra just for us.
                                try (Infrastructure infra = infraFactory.create(
                                        benchmarkSpec.location(), benchmarkSpec.output(),
                                        List.copyOf(benchmarkSpec.run().nixosConfigurations()))) {
                                    infra.run(benchmarkSpec.output(), benchmarkSpec.run(), benchmarkSpec.loadVariant(), benchmarkSpec.phaseUpdater());
                                    benchmarkSpec.phaseUpdater().update(BenchmarkPhase.SHUTTING_DOWN);
                                }
                                benchmarkSpec.phaseUpdater().update(BenchmarkPhase.DONE);
                                semaphore.release();
                            }
                        } catch (Exception e) {
                            benchmarkSpec.phaseUpdater().update(BenchmarkPhase.FAILED);
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
                })
                .toList();
        Future<?> progressTask = executor.submit(() -> {
            try {
                phaseTracker.trackLoop();
            } catch (IOException e) {
                LOG.error("Error in phase tracker", e);
            }
        });
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
            if (sharedInfrastructure != null) {
                for (Infrastructure infrastructure : sharedInfrastructure) {
                    if (infrastructure != null) {
                        try {
                            infrastructure.close();
                        } catch (Exception e) {
                            LOG.error("Failed to close shared infrastructure", e);
                        }
                    }
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

    private record BenchmarkSpec(
            int repetition,
            OciLocation location,
            FrameworkRun run,
            LoadVariant loadVariant,
            String name,
            Path output,
            PhaseTracker.PhaseUpdater phaseUpdater
    ) {
    }

    /**
     * Configuration for the benchmark suite.
     *
     * @param name               Name of the Nix-defined benchmark suite
     * @param repetitions        Number of repetitions for each run. If you define multiple {@link OciLocation}s, each
     *                           repetition will run on a different location, if possible
     * @param maxConcurrentRuns  Maximum number of concurrent runs, to avoid running into resource limits (only for
     *                           {@link InfrastructureMode#INFRASTRUCTURE_PER_RUN})
     * @param infrastructureMode How infrastructure should be reused between runs
     */
    @ConfigurationProperties("suite")
    public record SuiteConfiguration(
            String name,
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
            @io.micronaut.core.annotation.Nullable FrameworkRun.Profiling profiling,
            LoadVariant load,
            int repetition,
        BenchmarkMetadata.InstanceType sutSpecs
    ) {
    }
}
