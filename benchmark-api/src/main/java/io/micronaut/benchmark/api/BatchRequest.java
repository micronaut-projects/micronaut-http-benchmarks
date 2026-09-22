package io.micronaut.benchmark.api;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Serdeable
public record BatchRequest(List<ExperimentRequest> experiments) {
    public BatchRequest {
        experiments = List.copyOf(experiments);
        if (experiments.isEmpty()) {
            throw new IllegalArgumentException("Empty batch");
        }
    }
}
