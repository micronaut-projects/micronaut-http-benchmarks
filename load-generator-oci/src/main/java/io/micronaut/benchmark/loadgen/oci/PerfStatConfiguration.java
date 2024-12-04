package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

/**
 * Optionally run a server-under-test with {@code perf stat}.
 *
 * @param enabled  {@code true} to enable perf
 * @param interval The measurement interval
 */
@ConfigurationProperties("perf-stat")
public record PerfStatConfiguration(
        boolean enabled,
        Duration interval
) {
    String asCommandPrefix() {
        if (!enabled) {
            return "";
        }
        return "perf stat -I " + interval.toMillis() + " ";
    }
}
