package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Native-image-specific configuration.
 *
 * @param version       The graal version to use (e.g. {@code 21}).
 * @param optionChoices Additional option choices that should compete against one another. You can use this to e.g.
 *                      test different GC setups.
 * @param prefixOptions Additional options specific to a {@link JavaRunFactory#createJavaRuns(String)} type prefix
 *                      (i.e. specific to a framework)
 */
@ConfigurationProperties("variants.native-image")
public record NativeImageConfiguration(int version, List<String> optionChoices, Map<String, String> prefixOptions) {
}
