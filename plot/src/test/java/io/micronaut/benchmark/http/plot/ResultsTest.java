package io.micronaut.benchmark.http.plot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultsTest {
    @TempDir
    Path root;

    Path result(String name, String state, int mean) throws Exception {
        Path directory = Files.createDirectory(root.resolve(name));
        Files.writeString(directory.resolve("run.json"), "{\"state\":\"" + state + "\"}");
        Files.writeString(directory.resolve("metadata.json"), """
                {"name":"same-sut", "type":"example", "parameters":{},
                 "profiling":{"tool":"perf","artifact":"profile.data"}, "profileCoverage":"process-lifetime",
                 "load":{"name":"https2-small","protocol":{"protocol":"HTTPS2","sharedConnections":1,
                   "pipeliningLimit":1,"maxHttp2Streams":1,"compileOps":1,"ops":[100],"sla":{}},
                   "definition":{"name":"small","uri":"/small"}},
                 "sutSpecs":{"shape":"test","ocpus":1,"memoryInGb":1,"platform":"x86_64-linux","diskPerformanceUnits":10}}
                """);
        Files.writeString(directory.resolve("output.json"), """
                {"info":{"errors":[]},"failures":[],"stats":[
                  {"name":"warmup","phase":"warmup","total":{"summary":{"startTime":1000,"endTime":2000,"minResponseTime":0,"meanResponseTime":0,"stdDevResponseTime":0,"maxResponseTime":0,"requestCount":0,"responseCount":0,"invalid":0,"connectionErrors":0,"requestTimeouts":0,"internalErrors":0,"blockedTime":0,"percentileResponseTime":{},"extensions":{}}}},
                  {"name":"main/0","phase":"main","total":{"summary":{"startTime":2000,"endTime":62000,
                    "requestCount":6000,"responseCount":5940,"meanResponseTime":%d,
                    "percentileResponseTime":{"50.0":1000000,"99.0":5000000},"invalid":1,"minResponseTime":0,"maxResponseTime":5000000,"stdDevResponseTime":0,"connectionErrors":0,"requestTimeouts":0,"internalErrors":0,"blockedTime":0,"extensions":{}}}}
                ]}
                """.formatted(mean));
        return directory;
    }

    @Test
    void readsExplicitFoldersAndExcludesIncompleteMeasurementsFromPlots() throws Exception {
        Path first = result("first", "SUCCEEDED", 2000000);
        result("cancelled", "CANCELLED", 2000000);
        Path second = result("second", "SUCCEEDED", 3000000);
        var index = Results.index(root);
        assertEquals(List.of("first", "second"), index.stream().map(p -> p.name()).toList());
        assertEquals(".", Results.index(first).getFirst().name());
        var summary = Results.summary(first);
        assertEquals(1, summary.phases().size());
        assertEquals(60, summary.phases().getFirst().seconds());
        assertEquals(99, summary.phases().getFirst().responsesPerSecond());
        assertEquals(2, summary.phases().getFirst().meanLatencyMs());
        assertEquals(5, summary.phases().getFirst().p99LatencyMs());
        assertEquals("process-lifetime", summary.profileCoverage());
        assertEquals(50.0, Results.compare(first, second).deltas().getFirst().get("meanLatencyPercent"));
    }

    @Test
    void readsPartialFailureStatisticsWithoutDaemonOrGlobalIndex() throws Exception {
        Path directory = result("failed", "FAILED", 2000000);
        Files.move(directory.resolve("output.json"), directory.resolve("output-failed.json"));
        assertEquals("FAILED", Results.summary(directory).state());
        assertTrue(Results.index(root).isEmpty());
    }
}
