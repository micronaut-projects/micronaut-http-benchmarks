package io.micronaut.benchmark.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.Map;

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RunRecord(String id, ExperimentRequest request, String directory, String state,
                        String experiment, String environmentId, Instant submitted, Instant started,
                        Instant finished, String failure, Map<String, Instant> timings) {
    public boolean terminal() {
        return finished != null;
    }
}
