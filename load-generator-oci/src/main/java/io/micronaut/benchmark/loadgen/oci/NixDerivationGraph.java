package io.micronaut.benchmark.loadgen.oci;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class NixDerivationGraph {
    private static final String STORE_PREFIX = "/nix/store/";
    private static final String PGO_DIRECTORY = "/var/lib/sut/pgo";

    private NixDerivationGraph() {
    }

    static List<String> profileDependentOutputs(JsonNode derivationShow) {
        JsonNode derivations = derivationShow.path("derivations");
        if (derivations.isMissingNode()) {
            derivations = derivationShow;
        }
        if (!derivations.isObject()) {
            throw new IllegalStateException("Malformed nix derivation show result");
        }

        Map<String, JsonNode> derivationMap = new LinkedHashMap<>();
        derivations.properties().forEach(entry -> derivationMap.put(normalizeDerivationPath(entry.getKey()), entry.getValue()));
        Map<String, Set<String>> reverseDependencies = new LinkedHashMap<>();
        Set<String> marked = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> entry : derivationMap.entrySet()) {
            JsonNode derivation = entry.getValue();
            if (!derivation.isObject()) {
                throw new IllegalStateException("Malformed derivation " + entry.getKey());
            }
            if (isProfileDependent(derivation)) {
                marked.add(entry.getKey());
            }
            JsonNode inputs = derivation.path("inputs").path("drvs");
            if (inputs.isMissingNode()) {
                inputs = derivation.path("inputDrvs");
            }
            if (!inputs.isMissingNode() && !inputs.isObject()) {
                throw new IllegalStateException("Malformed derivation inputs for " + entry.getKey());
            }
            inputs.properties().forEach(input -> reverseDependencies
                    .computeIfAbsent(normalizeDerivationPath(input.getKey()), ignored -> new LinkedHashSet<>())
                    .add(entry.getKey()));
        }

        List<String> pending = new ArrayList<>(marked);
        for (int index = 0; index < pending.size(); index++) {
            for (String dependent : reverseDependencies.getOrDefault(pending.get(index), Set.of())) {
                if (marked.add(dependent)) {
                    pending.add(dependent);
                }
            }
        }
        return marked.stream()
                .flatMap(derivationPath -> outputPaths(derivationPath, derivationMap.get(derivationPath)).stream())
                .toList();
    }

    private static List<String> outputPaths(String derivationPath, JsonNode derivation) {
        JsonNode outputEntries = derivation.path("outputs");
        if (!outputEntries.isObject()) {
            throw new IllegalStateException("Malformed derivation outputs for " + derivationPath);
        }
        List<String> outputs = new ArrayList<>();
        outputEntries.properties().forEach(output -> {
            JsonNode value = output.getValue();
            JsonNode path = value.isObject() ? value.get("path") : value;
            if (path != null && !path.isNull()) {
                if (!path.isString()) {
                    throw new IllegalStateException("Malformed derivation output for " + derivationPath);
                }
                outputs.add(normalizeStorePath(path.stringValue(), false));
            }
        });
        return outputs;
    }

    private static boolean isProfileDependent(JsonNode derivation) {
        JsonNode env = derivation.path("env");
        JsonNode noChroot = env.get("__noChroot");
        return env.isObject()
                && noChroot != null
                && (noChroot.asBoolean(false) || "1".equals(noChroot.stringValue()) || "true".equalsIgnoreCase(noChroot.stringValue()))
                && (env.toString().contains(PGO_DIRECTORY) || derivation.path("args").toString().contains(PGO_DIRECTORY));
    }

    private static String normalizeDerivationPath(String path) {
        return normalizeStorePath(path, true);
    }

    private static String normalizeStorePath(String path, boolean derivation) {
        String normalized = path.startsWith(STORE_PREFIX) ? path : STORE_PREFIX + path;
        String basename = normalized.substring(STORE_PREFIX.length());
        if (!normalized.startsWith(STORE_PREFIX) || basename.isEmpty() || basename.contains("/") || (derivation && !basename.endsWith(".drv"))) {
            throw new IllegalStateException("Malformed nix store path: " + path);
        }
        return normalized;
    }
}
