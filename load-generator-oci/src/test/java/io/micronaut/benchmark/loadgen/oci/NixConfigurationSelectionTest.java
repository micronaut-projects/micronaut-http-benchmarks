package io.micronaut.benchmark.loadgen.oci;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NixConfigurationSelectionTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void selectsEachPrebuiltPgoCaseWithoutChangingRunIdentity() {
        BenchmarkMetadata metadata = metadata(Map.of(
                "http1", Map.of("small", "pgo-http1-small", "large", "pgo-http1-large"),
                "https2", Map.of("small", "pgo-https2-small", "large", "pgo-https2-large")
        ));
        FrameworkRun run = new NixRunSet(metadata).getRuns().getFirst();
        List<LoadVariant> cases = new LoadManager(metadata).getLoadVariants();

        assertEquals("standard-micronaut-pgo", run.name());
        assertEquals(List.of("pgo-http1-small", "pgo-https2-small", "pgo-http1-large", "pgo-https2-large"),
                cases.stream().map(run::nixosConfiguration).map(FrameworkRun.NixosConfiguration::name).toList());
    }

    @Test
    void ordinaryRunSharesOneConfigurationAcrossCases() {
        BenchmarkMetadata metadata = metadata(Map.of(
                "http1", Map.of("small", "shared", "large", "shared"),
                "https2", Map.of("small", "shared", "large", "shared")
        ));
        FrameworkRun run = new NixRunSet(metadata).getRuns().getFirst();
        assertEquals(List.of(new FrameworkRun.NixosConfiguration("shared")),
                new LoadManager(metadata).getLoadVariants().stream().map(run::nixosConfiguration).distinct().toList());
    }

    @Test
    void missingProtocolOrDocumentFailsDuringCaseSelection() {
        BenchmarkMetadata metadata = metadata(Map.of("http1", Map.of("small", "available")));
        FrameworkRun run = new NixRunSet(metadata).getRuns().getFirst();
        List<LoadVariant> cases = new LoadManager(metadata).getLoadVariants();
        for (LoadVariant load : cases.subList(1, cases.size())) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> run.nixosConfiguration(load));
            assertTrue(error.getMessage().contains("standard-micronaut-pgo/"));
            assertTrue(error.getMessage().endsWith("/" + load.definition().name()));
        }
    }

    @Test
    void missingConfigurationMapFailsDuringCaseSelection() {
        BenchmarkMetadata metadata = metadata(null);
        FrameworkRun run = new NixRunSet(metadata).getRuns().getFirst();
        LoadVariant load = new LoadManager(metadata).getLoadVariants().getFirst();
        assertThrows(IllegalArgumentException.class, () -> run.nixosConfiguration(load));
    }

    private static BenchmarkMetadata metadata(Map<String, Map<String, String>> configurations) {
        return BenchmarkMetadata.parse(MAPPER, """
                {
                  "suites": {"standard": {
                    "runs": [{"type":"micronaut-native-pgo", "name":"standard-micronaut-pgo",
                              "parameters":{}, "nixosConfigurations":%s}],
                    "documents": [{"name":"small", "uri":"/small"}, {"name":"large", "uri":"/large"}],
                    "protocols": {
                      "http1": {"protocol":"HTTP1", "sharedConnections":1, "pipeliningLimit":1,
                                "maxHttp2Streams":1, "compileOps":1, "ops":[1], "sla":{"0.99":"1s"}},
                      "https2": {"protocol":"HTTPS2", "sharedConnections":1, "pipeliningLimit":1,
                                 "maxHttp2Streams":1, "compileOps":1, "ops":[1], "sla":{"0.99":"1s"}}
                    },
                    "statusRequest": {"name":"status", "uri":"/status"}
                  }},
                  "instanceTypes": {}, "benchmarkDefinitions":"."
                }
                """.formatted(MAPPER.writeValueAsString(configurations)), "standard");
    }
}
