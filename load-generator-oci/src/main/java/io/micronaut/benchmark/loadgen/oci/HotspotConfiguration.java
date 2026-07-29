package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.bind.annotation.Bindable;

import java.util.Map;

/**
 * Hotspot-specific configuration.
 *
 * @param version       The hotspot version to use (e.g. {@code 21}).
 * @param commonOptions VM flags to add to all hotspot invocations.
 * @param optionChoices Additional option choices that should compete against one another. You can use this to e.g.
 *                      test different GC setups.
 */
@ConfigurationProperties("variants.hotspot")
public record HotspotConfiguration(
        String version,
        @Bindable(defaultValue = "false") boolean graalvm,
        @Nullable String uri,
        String commonOptions,
        Map<String, String> optionChoices
) {
}
