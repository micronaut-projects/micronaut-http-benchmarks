package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties("variants.native-image")
public record NativeImageConfiguration(int version, List<String> optionChoices, Map<String, String> prefixOptions) {
}
