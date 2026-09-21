package io.micronaut.benchmark.loadgen.oci;

/**
 * Phases, for rough progress logging.
 */
public enum BenchmarkPhase {
    QUEUED,
    PREPARING_INFRASTRUCTURE,
    STARTING_INSTANCES,
    ACTIVATING_CONFIGURATION,
    STARTING_SERVER,
    BENCHMARKING,
    RESTORING_BOOTSTRAP,
    DONE,
    FAILED,
}
