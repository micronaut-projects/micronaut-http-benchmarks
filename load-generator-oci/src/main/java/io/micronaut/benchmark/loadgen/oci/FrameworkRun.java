package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.core.annotation.Nullable;
import org.apache.sshd.client.session.ClientSession;

import java.nio.file.Path;

/**
 * This interface represents a particular choice of framework and framework options that a HTTP benchmark can run
 * against.
 */
public interface FrameworkRun {
    /**
     * The type name of this run. This is used to toggle benchmarks
     * ({@link SuiteRunner.SuiteConfiguration#enabledRunTypes()}).
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
            ClientSession benchmarkServerClient,
            Path outputDirectory,
            OutputListener.Write log,
            BenchmarkClosure benchmarkClosure,
            PhaseTracker.PhaseUpdater progress) throws Exception;

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
