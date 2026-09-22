package io.micronaut.benchmark.loadgen.oci;

import java.nio.file.Path;

/**
 * Serialized environment operations. execute returns only after collection and reset have been attempted.
 */
interface ExecutionEnvironment {
    String id();

    void validate(PreparedExperiment experiment) throws Exception;

    void up(PhaseTracker.PhaseUpdater progress) throws Exception;

    void execute(PreparedExperiment experiment, Path directory, PhaseTracker.PhaseUpdater progress) throws Exception;

    void down() throws Exception;
}
