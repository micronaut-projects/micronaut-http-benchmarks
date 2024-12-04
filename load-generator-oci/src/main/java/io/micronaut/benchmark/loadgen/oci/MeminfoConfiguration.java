package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

/**
 * Regularly fetch {@code /proc/meminfo} of the SUT during the benchmark.
 *
 * @param enabled {@code true} if meminfo tracking should be enabled
 * @param interval The tracking interval
 */
@ConfigurationProperties("meminfo")
public record MeminfoConfiguration(
        boolean enabled,
        Duration interval
) {
}
