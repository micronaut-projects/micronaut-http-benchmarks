package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Singleton
public final class BenchmarkMetadata {
    private static final Logger LOG = LoggerFactory.getLogger(BenchmarkMetadata.class);

    private final Map<String, Suite> suites;
    private final Map<String, InstanceType> instanceTypes;
    private final Path benchmarkDefinitions;
    private Suite selectedSuite;
    private String selectedSuiteName;

    public BenchmarkMetadata(Nix nix, JsonMapper objectMapper, SuiteRunner.SuiteConfiguration suiteConfiguration) throws Exception {
        this(objectMapper.readValue(
                nix.buildBenchmarkMetadata(new OutputListener.Log(LOG, Level.DEBUG)),
                Document.class
        ), suiteConfiguration.name());
    }

    BenchmarkMetadata(Document document, String suiteName) {
        suites = Map.copyOf(document.suites());
        instanceTypes = Map.copyOf(document.instanceTypes());
        benchmarkDefinitions = Objects.requireNonNull(document.benchmarkDefinitions());
        selectSuite(suiteName);
    }

    static BenchmarkMetadata parse(ObjectMapper objectMapper, String json, String suiteName) {
        return new BenchmarkMetadata(parseDocument(objectMapper, json), suiteName);
    }

    private static Document parseDocument(ObjectMapper objectMapper, String json) {
        return objectMapper.readValue(json, Document.class);
    }

    public Suite suite() {
        return selectedSuite;
    }

    public Suite selectSuite(String name) {
        selectedSuite = suites.get(name);
        if (selectedSuite == null) {
            throw new IllegalArgumentException("Unknown benchmark suite: " + name);
        }
        selectedSuiteName = name;
        return selectedSuite;
    }

    public Path benchmarkDefinition(SuiteRequest request, ProtocolSettings protocol) {
        return definitionPath(protocol, request.name(), "normal.yaml");
    }

    private Path definitionPath(ProtocolSettings protocol, String requestName, String fileName) {
        Path definitionsRoot = benchmarkDefinitions.toAbsolutePath().normalize();
        Path definition = definitionsRoot.resolve(selectedSuiteName)
                .resolve(protocol.protocol().name().toLowerCase())
                .resolve(requestName)
                .resolve(fileName)
                .normalize();
        if (!definition.startsWith(definitionsRoot)) {
            throw new IllegalArgumentException("Benchmark definition path escapes definitions root");
        }
        return definition;
    }

    public InstanceType instanceType(String name) {
        return instanceTypes.get(name);
    }

    record Document(Map<String, Suite> suites, Map<String, InstanceType> instanceTypes, Path benchmarkDefinitions) {
    }

    public record Suite(
            List<NixFrameworkMetadata> runs,
            List<SuiteRequest> documents,
            Map<String, ProtocolSettings> protocols,
            SuiteRequest statusRequest
    ) {
        public Suite {
            runs = List.copyOf(Objects.requireNonNull(runs));
            documents = List.copyOf(Objects.requireNonNull(documents));
            protocols = Map.copyOf(Objects.requireNonNull(protocols));
            statusRequest = Objects.requireNonNull(statusRequest);
        }
    }

    public record InstanceType(String shape, float ocpus, float memoryInGb, String platform, Integer diskPerformanceUnits) {
    }
}
