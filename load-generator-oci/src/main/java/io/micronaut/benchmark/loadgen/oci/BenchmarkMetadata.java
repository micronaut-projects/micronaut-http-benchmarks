package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Singleton
public final class BenchmarkMetadata {
    private static final Logger LOG = LoggerFactory.getLogger(BenchmarkMetadata.class);

    private final List<NixFrameworkMetadata> frameworkRuns;
    private final Map<String, InstanceType> instanceTypes;

    public BenchmarkMetadata(Nix nix, ObjectMapper objectMapper) throws Exception {
        this(objectMapper.readValue(
                new String(nix.buildBenchmarkMetadata(new OutputListener.Log(LOG, Level.DEBUG)), StandardCharsets.UTF_8),
                Document.class
        ));
    }

    BenchmarkMetadata(Document document) {
        frameworkRuns = List.copyOf(document.frameworkRuns());
        instanceTypes = Map.copyOf(document.instanceTypes());
    }

    static BenchmarkMetadata parse(ObjectMapper objectMapper, String json) {
        return new BenchmarkMetadata(parseDocument(objectMapper, json));
    }

    private static Document parseDocument(ObjectMapper objectMapper, String json) {
        try {
            return objectMapper.readValue(json, Document.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid benchmark metadata", e);
        }
    }

    public List<NixFrameworkMetadata> frameworkRuns() {
        return frameworkRuns;
    }

    public InstanceType instanceType(String name) {
        return instanceTypes.get(name);
    }

    record Document(List<NixFrameworkMetadata> frameworkRuns, Map<String, InstanceType> instanceTypes) {
    }

    public record InstanceType(String shape, float ocpus, float memoryInGb, String platform, Integer diskPerformanceUnits) {
    }
}
