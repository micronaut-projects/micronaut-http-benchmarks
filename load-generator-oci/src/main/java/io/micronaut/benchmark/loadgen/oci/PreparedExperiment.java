package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.api.Artifact;
import io.micronaut.benchmark.api.ExperimentRequirements;
import io.micronaut.benchmark.api.Nix;
import io.micronaut.benchmark.api.ThroughputSearch;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public record PreparedExperiment(Path system, List<Artifact> artifacts, ExperimentRequirements requirements, ThroughputSearch search) {
    public PreparedExperiment(Path system, List<Artifact> artifacts, ExperimentRequirements requirements) {
        this(system, artifacts, requirements, null);
    }
    public static PreparedExperiment load(Path output, Path resultDirectory, JsonMapper mapper) throws IOException {
        Path system = Nix.checkStorePath(output.resolve("system").toRealPath().toString(), false);
        List<Artifact> artifacts = List.of(mapper.readValue(Files.readAllBytes(output.resolve("artifacts.json")), Artifact[].class));
        for (int i = 0; i < artifacts.size(); i++)
            for (int j = 0; j < i; j++) {
                Artifact a = artifacts.get(i), b = artifacts.get(j);
                if (overlap(a.path(), b.path()) || overlap(a.remote(), b.remote())) {
                    throw new IOException("Overlapping artifact paths");
                }
            }
        ExperimentRequirements requirements = mapper.readValue(Files.readAllBytes(output.resolve("requirements.json")), ExperimentRequirements.class);
        if (requirements.version() != 1) {
            throw new IOException("Unsupported experiment contract version: " + requirements.version());
        }
        for (String file : List.of("metadata.json", "hyperfoil.yaml")) {
            Files.copy(output.resolve(file), resultDirectory.resolve(file));
        }
        Path data = output.resolve("hyperfoil-data");
        if (Files.exists(data)) {
            // Retain immutable payload files through the experiment's Nix closure.
            Files.createSymbolicLink(resultDirectory.resolve("hyperfoil-data"), data.toRealPath());
        }
        ThroughputSearch search = null;
        if (Files.exists(output.resolve("search.json"))) {
            Files.copy(output.resolve("search.json"), resultDirectory.resolve("search.json"));
            search = mapper.readValue(output.resolve("search.json").toFile(), ThroughputSearch.class);
            search.discovery(); // Validate the phase count before provisioning infrastructure.
        }
        return new PreparedExperiment(system, artifacts, requirements, search);
    }

    private static boolean overlap(String a, String b) {
        return Path.of(a).startsWith(b) || Path.of(b).startsWith(a);
    }
}
