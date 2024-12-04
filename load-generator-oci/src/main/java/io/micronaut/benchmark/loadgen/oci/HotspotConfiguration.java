package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;

/**
 * Hotspot-specific configuration.
 *
 * @param version       The hotspot version to use (e.g. {@code 21}).
 * @param commonOptions VM flags to add to all hotspot invocations.
 * @param optionChoices Additional option choices that should compete against one another. You can use this to e.g.
 *                      test different GC setups.
 */
@ConfigurationProperties("variants.hotspot")
public record HotspotConfiguration(int version, String commonOptions, List<String> optionChoices) {
}
