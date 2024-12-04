package io.micronaut.benchmark.loadgen.oci;

import java.util.List;

/**
 * A set of {@link FrameworkRun}s. Implementations are Micronaut framework {@link jakarta.inject.Singleton}s.
 */
public interface FrameworkRunSet {
    List<? extends FrameworkRun> getRuns();
}
