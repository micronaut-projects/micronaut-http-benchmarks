package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Main runner for the benchmark suite.
 */
@Singleton
public final class SuiteRunner {
    private static final Logger LOG = LoggerFactory.getLogger(SuiteRunner.class);

    private final CompartmentCleaner compartmentCleaner;
    private final OciLocation location;
    private final Infrastructure.Factory infraFactory;
    private final LoadManager loadManager;
    private final List<FrameworkRun> runs;
    private final ObjectMapper objectMapper;

    public SuiteRunner(CompartmentCleaner compartmentCleaner,
                       OciLocation location,
                       Infrastructure.Factory infraFactory,
                       LoadManager loadManager,
                       NixRunSet runSet,
                       ObjectMapper objectMapper) {
        this.compartmentCleaner = compartmentCleaner;
        this.location = location;
        this.infraFactory = infraFactory;
        this.loadManager = loadManager;
        this.runs = runSet.getRuns();
        this.objectMapper = objectMapper;
    }

    /**
     * Clean the benchmark compartment in the configured region.
     */
    public void clean() throws Exception {
        compartmentCleaner.cleanCompartment(location, false);
    }

    public void run() throws Exception {
        Path outputDir = Path.of("output");
        Files.createDirectories(outputDir);

        List<LoadVariant> loadVariants = loadManager.getLoadVariants();
        List<BenchmarkSpec> benchmarkSpecs = new ArrayList<>();
        PhaseTracker phaseTracker = new PhaseTracker(objectMapper, outputDir);
        for (FrameworkRun run : runs) {
            for (LoadVariant loadVariant : loadVariants) {
                FrameworkRun.NixosConfiguration configuration = run.nixosConfiguration(loadVariant);
                String name = run.name() + "-" + loadVariant.name();
                PhaseTracker.PhaseUpdater phaseUpdater = phaseTracker.updater(name);
                phaseUpdater.update(BenchmarkPhase.QUEUED);
                benchmarkSpecs.add(new BenchmarkSpec(run, loadVariant, configuration, name,
                        outputDir.resolve(name), phaseUpdater));
            }
        }
        Collections.shuffle(benchmarkSpecs);

        BenchmarkMetadata.InstanceType sutSpecs = infraFactory.compute()
                .getInstanceType(Infrastructure.BENCHMARK_SERVER_INSTANCE_TYPE);
        List<BenchmarkParameters> index = benchmarkSpecs.stream()
                .map(spec -> new BenchmarkParameters(spec.name(), spec.run().type(), spec.run().parameters(),
                        spec.run().profiling(), spec.loadVariant(), sutSpecs))
                .toList();
        Path newIndex = outputDir.resolve("index.new.json");
        objectMapper.writeValue(newIndex.toFile(), index);
        LOG.info("There are {} benchmarks to run", benchmarkSpecs.size());

        try (AutoCloseable progress = phaseTracker.start();
             AutoCloseable cleanup = this::cleanAfterRun) {
            clean();
            try (Infrastructure infrastructure = infraFactory.create(location, outputDir.resolve("infra"),
                    benchmarkSpecs.stream().map(BenchmarkSpec::configuration).toList())) {
                for (BenchmarkSpec spec : benchmarkSpecs) {
                    MdcTracker.withMdc(spec.name(), () -> {
                        try {
                            if (Thread.currentThread().isInterrupted()) {
                                throw new InterruptedException("Benchmark interrupted");
                            }
                            infrastructure.run(spec.output(), spec.run(), spec.loadVariant(), spec.configuration(), spec.phaseUpdater());
                            spec.phaseUpdater().update(BenchmarkPhase.DONE);
                        } catch (Exception e) {
                            spec.phaseUpdater().update(BenchmarkPhase.FAILED);
                            throw e;
                        }
                        return null;
                    });
                }
            }
        }
        Files.move(newIndex, outputDir.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
        LOG.info("All benchmarks complete");
    }

    private void cleanAfterRun() throws Exception {
        // An interrupted Nix build may restore the interrupt flag. Allow cleanup to wait for OCI resources.
        boolean interrupted = Thread.interrupted();
        try {
            clean();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record BenchmarkSpec(
            FrameworkRun run,
            LoadVariant loadVariant,
            FrameworkRun.NixosConfiguration configuration,
            String name,
            Path output,
            PhaseTracker.PhaseUpdater phaseUpdater
    ) {
    }

    /**
     * Configuration for the benchmark suite.
     *
     * @param name Name of the Nix-defined benchmark suite
     */
    @ConfigurationProperties("suite")
    public record SuiteConfiguration(String name) {
    }

    public record BenchmarkParameters(
            String name,
            String type,
            Object parameters,
            @io.micronaut.core.annotation.Nullable FrameworkRun.Profiling profiling,
            LoadVariant load,
            BenchmarkMetadata.InstanceType sutSpecs
    ) {
    }
}
