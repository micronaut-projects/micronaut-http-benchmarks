package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

@Singleton
public final class BenchmarkMetadata {
    private static final Logger LOG = LoggerFactory.getLogger(BenchmarkMetadata.class);

    private final List<NixFrameworkMetadata> frameworkRuns;
    private final Map<String, InstanceType> instanceTypes;

    public BenchmarkMetadata(Nix nix, JsonMapper objectMapper) throws Exception {
        this(objectMapper.readValue(
                nix.buildBenchmarkMetadata(new OutputListener.Log(LOG, Level.DEBUG)),
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
        return objectMapper.readValue(json, Document.class);
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
