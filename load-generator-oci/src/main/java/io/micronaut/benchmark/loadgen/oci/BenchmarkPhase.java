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
    PGO,
    BENCHMARKING,
    RESTORING_BOOTSTRAP,
    SHUTTING_DOWN,
    DONE,
    FAILED,
}
