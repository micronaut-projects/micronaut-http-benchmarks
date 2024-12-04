package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * Configuration for async-profiler support.
 *
 * @param enabled Whether async-profiler should be enabled. This may have an impact on performance, so don't do this in
 *                a measurement benchmark.
 * @param path    The path to {@code libasyncProfiler.so}
 * @param args    Args to pass to async-profiler. e.g. {@code start,event=cpu,file=flamegraph.html}
 * @param outputs Output files to copy from the server-under-test VM after profiling is done, e.g.
 *                {@code flamegraph.html}
 */
@ConfigurationProperties("variants.hotspot.async-profiler")
public record AsyncProfilerConfiguration(
        boolean enabled,
        Path path,
        String args,
        List<String> outputs
) {
}
