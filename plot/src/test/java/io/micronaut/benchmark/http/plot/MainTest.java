package io.micronaut.benchmark.http.plot;

import io.micronaut.benchmark.api.BenchmarkResult;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MainTest {
    @Test
    void readsAndWritesIndexWithoutRepetition() {
        JsonMapper mapper = JsonMapper.builder().build();
        List<BenchmarkResult> index = mapper.readValue("""
                [{
                  "name":"micronaut-http1-small", "type":"micronaut", "parameters":{}, "profiling":null,
                  "load": {
                    "name":"http1-small",
                    "protocol": {"protocol":"HTTP1", "sharedConnections":1, "pipeliningLimit":1,
                                 "maxHttp2Streams":1, "compileOps":1, "ops":[10], "sla":{"0.99":"1s"}},
                    "definition": {"name":"small", "uri":"/small"}
                  },
                  "sutSpecs": {"shape":"VM.Standard.E4.Flex", "ocpus":4, "memoryInGb":16,
                               "platform":"x86_64-linux", "diskPerformanceUnits":10}
                }]
                """, new TypeReference<>() { });
        assertEquals("micronaut-http1-small", index.getFirst().name());
        assertEquals("/small", index.getFirst().load().definition().uri());
        assertEquals("x86_64-linux", index.getFirst().sutSpecs().platform());
        String json = mapper.writeValueAsString(index);
        assertFalse(mapper.readTree(json).get(0).has("repetition"));
        assertEquals(index, mapper.readValue(json, new TypeReference<List<BenchmarkResult>>() {
        }));
    }

    @Test
    void loomSupportMapsNixThreadingMetadata() {
        assertEquals("off", Main.loomSupport(null));
        assertEquals("off", Main.loomSupport(Map.of()));
        assertEquals("off", Main.loomSupport(Map.of("threading", "default")));
        assertEquals("on", Main.loomSupport(Map.of("threading", "virtual")));
        assertEquals("carried", Main.loomSupport(Map.of("threading", "loom-carrier")));
        assertThrows(IllegalArgumentException.class,
                () -> Main.loomSupport(Map.of("threading", "unknown")));
    }
}
