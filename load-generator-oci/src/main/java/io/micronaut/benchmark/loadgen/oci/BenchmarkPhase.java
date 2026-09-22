package io.micronaut.benchmark.loadgen.oci;

/**
 * Phases, for rough progress logging.
 */
public enum BenchmarkPhase {
    QUEUED,
    PREPARING_INFRASTRUCTURE,
    STARTING_INSTANCES,
    PUBLISHING_CLOSURE,
    ACTIVATING_CONFIGURATION,
    STARTING_SERVER,
    BENCHMARKING,
    COLLECTING_ARTIFACTS,
    RESTORING_BOOTSTRAP,
    DONE,
    FAILED,
}
