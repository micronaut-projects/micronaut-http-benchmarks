package io.micronaut.benchmark.cli;

import io.micronaut.benchmark.api.ExperimentRequest;
import io.micronaut.benchmark.api.Nix;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class Preparation {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Nix nix;
    private final String flake;
    private final List<String> options = new ArrayList<>(List.of("--no-write-lock-file"));

    Preparation(Nix nix, String flake, Map<String, String> inputs) {
        this.nix = nix;
        this.flake = Files.isDirectory(Path.of(flake)) ? Path.of(flake).toAbsolutePath().normalize().toString() : flake;
        inputs.forEach((name, reference) -> options.addAll(List.of("--override-input", name, reference)));
    }

    JsonNode catalog() throws Exception {
        var args = new ArrayList<>(List.of("eval", "--json", flake + "#lib.catalog"));
        args.addAll(options);
        return nix.json(args, System.err);
    }

    ExperimentRequest prepare(Map<String, Object> selection, Path outputRoot) throws Exception {
        var args = new ArrayList<>(List.of("eval", "--raw", flake + "#lib.mkExperiment", "--apply",
                "f: (f (builtins.fromJSON " + nix.expressionString(JSON.writeValueAsString(selection)) + ")).drvPath"));
        args.addAll(options);
        String derivation = nix.capture(args, System.err);
        return new ExperimentRequest(derivation, "out", outputRoot.toAbsolutePath().normalize().toString(),
                Map.of("source", flake, "selection", JSON.writeValueAsString(selection)));
    }
}
