package io.micronaut.benchmark.http.plot;

import io.micronaut.benchmark.api.BenchmarkResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LoadGroupTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static BenchmarkResult result(String type, String micronaut, String json) {
        return MAPPER.readValue("""
                {
                  "name":"x", "type":"%s", "profiling":null,
                  "parameters":{"compileConfiguration":{"micronaut":"%s", "json":"%s"}},
                  "load": {
                    "name":"http1-small",
                    "protocol": {"protocol":"HTTP1", "sharedConnections":1, "pipeliningLimit":1,
                                 "maxHttp2Streams":1, "compileOps":1, "ops":[10], "sla":{"0.99":"1s"}},
                    "definition": {"name":"small", "uri":"/small"}
                  },
                  "sutSpecs": {"shape":"VM.Standard.E4.Flex", "ocpus":4, "memoryInGb":16}
                }
                """.formatted(type, micronaut, json), BenchmarkResult.class);
    }

    @Test
    void legendWithThreeVaryingDiscriminators() {
        LoadGroup group = new LoadGroup();
        for (String type : new String[]{"micronaut", "pure-netty"}) {
            for (String micronaut : new String[]{"4.9", "4.10"}) {
                for (String json : new String[]{"jackson", "serde"}) {
                    if (type.equals("pure-netty") && micronaut.equals("4.10") && json.equals("serde")) {
                        continue;
                    }
                    group.add(result(type, micronaut, json), null, null, null);
                }
            }
        }
        group.complete();

        StringBuilder html = new StringBuilder();
        group.emitColoredDiscriminators(html);
        String legend = html.toString();

        // type and Micronaut version are nested row headers, JSON implementation spans the columns
        assertTrue(legend.contains("<th class='sideways' rowspan='4'><span>type</span></th>"), legend);
        assertTrue(legend.contains("<th class='sideways' rowspan='4'><span>Micronaut version</span></th>"), legend);
        assertTrue(legend.contains("<th rowspan='2'>micronaut</th>"), legend);
        assertTrue(legend.contains("<th colspan='4'></th><th colspan='2'>JSON implementation</th>"), legend);
        assertEquals(7, Pattern.compile("<td style='background-color").matcher(legend).results().count());
        assertEquals(8, Pattern.compile("<td").matcher(legend).results().count());
    }
}
