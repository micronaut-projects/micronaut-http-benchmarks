package io.micronaut.benchmark.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micronaut.core.annotation.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BenchmarkResult(String name, String type, Object parameters, @Nullable ProfileMetadata profiling,
                              LoadVariant load, InstanceType sutSpecs) {
}
