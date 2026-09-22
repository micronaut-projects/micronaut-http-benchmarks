package io.micronaut.benchmark.api;

import io.micronaut.serde.annotation.Serdeable;

import java.nio.file.Path;
import java.util.Map;

@Serdeable
public record ExperimentRequest(String derivation, String output, String outputRoot, Map<String, String> annotations) {
    public ExperimentRequest {
        Nix.checkStorePath(derivation, true);
        output = output == null ? "out" : output;
        if (!output.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid output name");
        }
        if (!Path.of(outputRoot).isAbsolute()) {
            throw new IllegalArgumentException("outputRoot must be absolute");
        }
        annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
    }
}
