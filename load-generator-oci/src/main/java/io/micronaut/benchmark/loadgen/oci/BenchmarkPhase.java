package io.micronaut.benchmark.loadgen.oci;

/**
 * Phases, for rough progress logging.
 */
public enum BenchmarkPhase {
    BEFORE,
    CREATING_VCN,
    SETTING_UP_NETWORK,
    SETTING_UP_INSTANCES,
    DEPLOYING_OS,
    INSTALLING_SOFTWARE,
    DEPLOYING_SERVER,
    BUILDING_PGO_IMAGE,
    PGO,
    BUILDING_IMAGE,
    BENCHMARKING,
    SHUTTING_DOWN,
    DONE,
    FAILED,
}
