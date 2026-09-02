package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.core.annotation.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * This interface represents a particular choice of framework and framework options that a HTTP benchmark can run
 * against.
 */
public interface FrameworkRun {
    /**
     * The type name of this run, stored in the benchmark result index.
     *
     * @return The type name
     */
    String type();

    /**
     * The full name of this run, including config options. This is used as the folder name for the results, so it
     * should be unique between runs in the same suite.
     *
     * @return The run name
     */
    String name();

    /**
     * Optional parameter data structure. This will be saved in the benchmark index and used by the visualization.
     *
     * @return The benchmark parameters
     */
    @Nullable
    Object parameters();

    @Nullable
    Profiling profiling();

    List<NixosConfiguration> nixosConfigurations();

    record NixosConfiguration(String name, boolean dynamicPgo) {
    }

    record Profiling(String tool, String artifact) {
        public Profiling {
            Path artifactPath = Path.of(artifact);
            if (artifact.isBlank() || artifactPath.isAbsolute() || artifactPath.getNameCount() != 1 || artifact.equals(".") || artifact.equals("..") || artifact.contains("\\")) {
                throw new IllegalArgumentException("Profiling artifact must be a single filename: " + artifact);
            }
        }

        public String remotePath() {
            return "/var/lib/sut/" + artifact;
        }
    }

    /**
     * Set up the benchmark environment, and run this benchmark. Note that the server-under-test VM may not be "clean",
     * in some setups it has been used for other benchmarks before.
     *
     * @param benchmarkServerClient The SSH connection to the server the SUT will run on
     * @param outputDirectory       The output directory for logs and results
     * @param log                   The server log
     * @param benchmarkClosure      The closure for actual benchmark calls
     * @param progress              A callback for progress updates
     */
    void setupAndRun(
            CommandRunner benchmarkServerClient,
            Path outputDirectory,
            OutputListener.Write log,
            BenchmarkClosure benchmarkClosure,
            ConfigurationActivator configurationActivator,
            PhaseTracker.PhaseUpdater progress) throws Exception;

    interface ConfigurationActivator {
        NixCacheAccess resolve(String configuration) throws Exception;

        void activate(Activation request) throws Exception;
    }

    record Activation(String configuration, String output, PhaseTracker.PhaseUpdater progress) {
    }

    /**
     * Called by {@link #setupAndRun} once the server has been set up, to run the benchmark load.
     */
    interface BenchmarkClosure {
        /**
         * Run a normal benchmark load (including warmup) and track the results.
         *
         * @param progress The progress updater
         */
        void benchmark(PhaseTracker.PhaseUpdater progress) throws Exception;

        /**
         * Run a non-measuring benchmark load for profile-guided optimization before the actual benchmark run.
         *
         * @param progress The progress updater
         */
        void pgoLoad(PhaseTracker.PhaseUpdater progress) throws Exception;
    }
}
