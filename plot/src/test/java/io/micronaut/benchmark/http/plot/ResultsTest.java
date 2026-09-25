package io.micronaut.benchmark.http.plot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.micronaut.benchmark.api.ThroughputResult;
import io.micronaut.benchmark.api.ThroughputSearch;
import io.micronaut.benchmark.api.ThroughputStage;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResultsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
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
        assertNull(summary.phases().getFirst().p95LatencyMs());
        assertEquals("process-lifetime", summary.profileCoverage());
        assertEquals(50.0, Results.compare(first, second).deltas().getFirst().get("meanLatencyPercent"));
    }

    @Test
    void readsP95FromHistogramWhenAbsentFromSummary() throws Exception {
        Path directory = result("histogram", "SUCCEEDED", 2000000);
        var raw = (ObjectNode) JSON.readTree(directory.resolve("output.json").toFile());
        var phase = (ObjectNode) raw.path("stats").get(1);
        phase.set("histogram", JSON.readTree("""
                {"percentiles":[
                  {"from":2000000,"to":3000000,"percentile":0.95,"count":50,"totalCount":950},
                  {"from":3000000,"to":5000000,"percentile":0.99,"count":40,"totalCount":990}]}
                """));
        JSON.writeValue(directory.resolve("output.json").toFile(), raw);
        assertEquals(3, Results.summary(directory).phases().getFirst().p95LatencyMs());
        ((ObjectNode) phase.path("total").path("summary").path("percentileResponseTime")).put("95.0", 2500000);
        JSON.writeValue(directory.resolve("output.json").toFile(), raw);
        assertEquals(2.5, Results.summary(directory).phases().getFirst().p95LatencyMs());
    }

    @Test
    void readsPartialFailureStatisticsWithoutDaemonOrGlobalIndex() throws Exception {
        Path directory = result("failed", "FAILED", 2000000);
        Files.move(directory.resolve("output.json"), directory.resolve("output-failed.json"));
        assertEquals("FAILED", Results.summary(directory).state());
        assertTrue(Results.index(root).isEmpty());
    }

    @Test
    void adaptiveComparisonsAllowDifferentRatesButRejectDifferentSlasAndSettings() throws Exception {
        Path first = adaptive("first", 100), second = adaptive("second", 200);
        var comparison = Results.compare(first, second);
        assertTrue(comparison.differences().isEmpty(), comparison.differences().toString());
        assertEquals(105, comparison.baseline().aggregate().medianPassingRate());
        assertEquals((205.0 / 105 - 1) * 100, comparison.throughputPercent());
        var metadata = (ObjectNode) JSON.readTree(second.resolve("metadata.json").toFile());
        ((ObjectNode) metadata.path("load").path("protocol").path("sla")).put("0.99", "10ms");
        JSON.writeValue(second.resolve("metadata.json").toFile(), metadata);
        assertTrue(Results.compare(first, second).differences().contains("Workload metadata differs"));
        assertNull(Results.compare(first, second).throughputPercent());
        var partial = JSON.readValue(first.resolve("throughput.json").toFile(), ThroughputResult.class);
        JSON.writeValue(first.resolve("throughput.json").toFile(), new ThroughputResult(partial.search(), List.of(partial.repetitions().getFirst())));
        assertNull(Results.summary(first).aggregate());
        assertEquals(1, Results.summary(first).throughput().repetitions().size());
    }

    Path adaptive(String name, int rate) throws Exception {
        Path directory = result(name, "SUCCEEDED", 2000000);
        var search = new ThroughputSearch("thorough", 100, 1000, "180s", "15s", "45s", 25, 2, 2, 2);
        var first = new ThroughputStage.Result("validation", "BRACKETED", rate, rate + 5, "SLA", List.of());
        var second = new ThroughputStage.Result("validation", "BRACKETED", rate + 10, rate + 15, "SLA", List.of());
        JSON.writeValue(directory.resolve("search.json").toFile(), search);
        JSON.writeValue(directory.resolve("throughput.json").toFile(), new ThroughputResult(search, List.of(
                new ThroughputResult.Repetition(1, "1/discovery", first, "1/validation", first),
                new ThroughputResult.Repetition(2, "2/discovery", first, "2/validation", second))));
        JSON.writeValue(directory.resolve("environment.json").toFile(), java.util.Map.of("id", name, "infrastructure", "same"));
        var metadata = (ObjectNode) JSON.readTree(directory.resolve("metadata.json").toFile());
        ((ObjectNode) metadata.path("load").path("protocol")).set("ops", JSON.valueToTree(List.of(rate)));
        JSON.writeValue(directory.resolve("metadata.json").toFile(), metadata);
        return directory;
    }

    @Test
    void chartsCombineProfilersButKeepDifferentSlasSeparate() throws Exception {
        Path first = adaptive("first", 100), second = adaptive("second", 200);
        var metadata = (ObjectNode) JSON.readTree(second.resolve("metadata.json").toFile());
        ((ObjectNode) metadata.path("profiling")).put("tool", "async-profiler").put("artifact", "profile.jfr");
        JSON.writeValue(second.resolve("metadata.json").toFile(), metadata);
        assertEquals(List.of("Profiling configuration differs"), Results.compare(first, second).differences());
        assertNull(Results.compare(first, second).throughputPercent());
        assertEquals(1, ThroughputCharts.groups(List.of(Results.summary(first), Results.summary(second))).size());
        ((ObjectNode) metadata.path("load").path("protocol").path("sla")).put("0.95", "200ms");
        JSON.writeValue(second.resolve("metadata.json").toFile(), metadata);
        assertEquals(2, ThroughputCharts.groups(List.of(Results.summary(first), Results.summary(second))).size());
    }

    @Test
    void adaptiveComparisonsRejectDifferentWarmupConcurrency() throws Exception {
        Path first = adaptive("first", 100), second = adaptive("second", 200);
        String workload = "phases:\n- warmup:\n    always:\n      users: %d\n      duration: 60s\n";
        Files.writeString(first.resolve("hyperfoil.yaml"), workload.formatted(50));
        Files.writeString(second.resolve("hyperfoil.yaml"), workload.formatted(200));
        var comparison = Results.compare(first, second);
        assertTrue(comparison.differences().contains("Warmup configuration differs"));
        assertNull(comparison.throughputPercent());
        Files.writeString(second.resolve("hyperfoil.yaml"), workload.formatted(50));
        assertTrue(Results.compare(first, second).differences().isEmpty());
    }

    @Test
    void adaptiveComparisonsRejectDifferentValidationDurations() throws Exception {
        Path first = adaptive("first", 100), second = adaptive("second", 200);
        var result = JSON.readValue(second.resolve("throughput.json").toFile(), ThroughputResult.class);
        var s = result.search();
        var longer = new ThroughputSearch(s.preset(), s.startRate(), s.maxRate(), s.warmupDuration(),
                s.discoveryDuration(), "90s", s.discoveryStep(), s.validationStep(), s.repetitions(), s.sessionLimitFactor());
        JSON.writeValue(second.resolve("search.json").toFile(), longer);
        JSON.writeValue(second.resolve("throughput.json").toFile(), new ThroughputResult(longer, result.repetitions()));
        var comparison = Results.compare(first, second);
        assertTrue(comparison.differences().contains("Throughput search settings differ"));
        assertNull(comparison.throughputPercent());
    }

    @Test
    void aggregateAndStageCurvesShareTheSavedFailureBoundary() throws Exception {
        Path parent = adaptive("parent", 100), stage = result("stage", "SUCCEEDED", 2000000);
        var stats = (ObjectNode) JSON.readTree(stage.resolve("output.json").toFile());
        var phases = stats.withArray("stats");
        ((ObjectNode) phases.get(0).path("total").path("summary")).put("requestCount", 100).put("responseCount", 100);
        ((ObjectNode) phases.get(1).path("total").path("summary")).put("responseCount", 6000).put("invalid", 0);
        phases.add(((ObjectNode) phases.get(1)).deepCopy().put("name", "main/1"));
        stats.withArray("failures").addObject().put("phase", "main/1").put("message", "Exceeded session limit");
        JSON.writeValue(stage.resolve("output.json").toFile(), stats);
        JSON.writeValue(stage.resolve("stage-plan.json").toFile(), new ThroughputStage("validation", 1000, List.of(
                new ThroughputStage.Phase("main/0", 100, 60000), new ThroughputStage.Phase("main/1", 110, 60000))));
        JSON.writeValue(stage.resolve("stage-completion.json").toFile(), new ThroughputStage.Completion(true, false,
                List.of("warmup", "main/0", "main/1")));
        String relative = "repetitions/1/validation";
        Files.createDirectories(parent.resolve(relative).getParent());
        Files.move(stage, parent.resolve(relative));
        var search = new ThroughputSearch("quick", 100, 200, "1s", "1s", "60s", 25, 5, 1, 2);
        var validation = Results.summary(parent.resolve(relative)).eligibility();
        JSON.writeValue(parent.resolve("throughput.json").toFile(), new ThroughputResult(search, List.of(
                new ThroughputResult.Repetition(1, "repetitions/1/discovery", validation, relative, validation))));
        var summary = Results.summary(parent);
        assertEquals(summary.stages().getFirst().eligibility(), summary.throughput().repetitions().getFirst().validation());
        assertEquals("BRACKETED", summary.throughput().repetitions().getFirst().validation().outcome());
        assertEquals(100, summary.aggregate().medianPassingRate());
        assertEquals(110, ThroughputCharts.data(List.of(summary)).runs().getFirst().bounds().getFirst().failing());
    }

    @Test
    void discoveryRampStatisticsStayInDiagnosticsWithoutBecomingFixedRatePoints() throws Exception {
        Path directory = result("discovery-ramp", "SUCCEEDED", 2000000);
        var stats = (ObjectNode) JSON.readTree(directory.resolve("output.json").toFile());
        var raw = stats.withArray("stats");
        ((ObjectNode) raw.get(0).path("total").path("summary")).put("requestCount", 100).put("responseCount", 100);
        ((ObjectNode) raw.get(1).path("total").path("summary")).put("responseCount", 6000).put("invalid", 0);
        var ramp = ((ObjectNode) raw.get(1)).deepCopy();
        ramp.put("name", "ramp/1");
        ((ObjectNode) ramp.path("total").path("summary")).put("endTime", 7000);
        raw.add(ramp);
        var next = ((ObjectNode) raw.get(1)).deepCopy();
        next.put("name", "main/1");
        raw.add(next);
        JSON.writeValue(directory.resolve("output.json").toFile(), stats);
        var plan = new ThroughputStage("discovery", 1000, List.of(
                new ThroughputStage.Phase("main/0", 100, 60000),
                new ThroughputStage.Phase("main/1", 125, 60000)), 5000L);
        JSON.writeValue(directory.resolve("stage-plan.json").toFile(), plan);
        JSON.writeValue(directory.resolve("stage-completion.json").toFile(), new ThroughputStage.Completion(true, false,
                List.of("warmup", "main/0", "ramp/1", "main/1")));
        var summary = Results.summary(directory);
        assertEquals(List.of("PASS", "RAMP_PASS", "PASS"), summary.phases().stream().map(Results.Phase::status).toList());
        assertEquals(List.of(100, 125), ThroughputCharts.data(List.of(summary)).runs().getFirst().curves().getFirst()
                .phases().stream().map(Results.Phase::targetRate).toList());
        assertTrue(ThroughputPlot.render(directory).contains("RAMP_PASS"));

        stats.withArray("failures").addObject().put("phase", "ramp/1").put("message", "Response time exceeded");
        JSON.writeValue(directory.resolve("output.json").toFile(), stats);
        summary = Results.summary(directory);
        assertEquals(List.of("PASS", "RAMP_FAIL", "EXCLUDED"), summary.phases().stream().map(Results.Phase::status).toList());
        assertEquals(List.of(100), ThroughputCharts.data(List.of(summary)).runs().getFirst().curves().getFirst()
                .phases().stream().map(Results.Phase::targetRate).toList());
        assertTrue(ThroughputPlot.render(directory).contains("RAMP_FAIL"));
    }

    @Test
    void adaptiveStageDiagnosticsRetainLaterPassesButComparisonsExcludeThem() throws Exception {
        Path directory = result("stage", "SUCCEEDED", 2000000);
        var stats = (ObjectNode) JSON.readTree(directory.resolve("output.json").toFile());
        var raw = stats.withArray("stats");
        ((ObjectNode) raw.get(0).path("total").path("summary")).put("requestCount", 100).put("responseCount", 100);
        ((ObjectNode) raw.get(1).path("total").path("summary")).put("responseCount", 6000).put("invalid", 0);
        for (int i = 1; i <= 2; i++) {
            var next = ((ObjectNode) raw.get(1)).deepCopy();
            next.put("name", "main/" + i);
            raw.add(next);
        }
        stats.withArray("failures").addObject().put("phase", "main/1").put("message", "Response time exceeded");
        JSON.writeValue(directory.resolve("output.json").toFile(), stats);
        var plan = new ThroughputStage("validation", 1000, List.of(
                new ThroughputStage.Phase("main/0", 100, 60000),
                new ThroughputStage.Phase("main/1", 110, 60000),
                new ThroughputStage.Phase("main/2", 121, 60000)));
        JSON.writeValue(directory.resolve("stage-plan.json").toFile(), plan);
        JSON.writeValue(directory.resolve("stage-completion.json").toFile(), new ThroughputStage.Completion(true, false,
                List.of("warmup", "main/0", "main/1", "main/2")));
        assertEquals(List.of("PASS", "FAIL", "EXCLUDED"), Results.summary(directory).phases().stream().map(Results.Phase::status).toList());
        var chart = ThroughputCharts.data(List.of(Results.summary(directory)));
        assertEquals(List.of(100, 110), chart.runs().getFirst().curves().getFirst().phases().stream().map(Results.Phase::targetRate).toList());
        assertEquals(100, chart.runs().getFirst().bounds().getFirst().passing());
        assertEquals(110, chart.runs().getFirst().bounds().getFirst().failing());
        assertEquals(1, Results.compare(directory, directory).deltas().size());
        assertTrue(ThroughputPlot.render(directory).contains("EXCLUDED"));
        stats.withArray("failures").addObject().put("phase", "main/1").put("message", "Exceeded session limit");
        JSON.writeValue(directory.resolve("output.json").toFile(), stats);
        var limited = Results.summary(directory);
        assertEquals(List.of("PASS", "FAIL", "EXCLUDED"), limited.phases().stream().map(Results.Phase::status).toList());
        var limitedChart = ThroughputCharts.data(List.of(limited));
        assertEquals(List.of(100, 110), limitedChart.runs().getFirst().curves().getFirst().phases().stream().map(Results.Phase::targetRate).toList());
        assertEquals(100, limitedChart.runs().getFirst().bounds().getFirst().passing());
        assertEquals(110, limitedChart.runs().getFirst().bounds().getFirst().failing());
        assertEquals(1, Results.compare(directory, directory).deltas().size());
        stats.withArray("failures").remove(0); // A session failure is sufficient without a latency failure.
        JSON.writeValue(directory.resolve("output.json").toFile(), stats);
        assertEquals("BRACKETED", Results.summary(directory).eligibility().outcome());
        assertEquals(110, Results.summary(directory).eligibility().firstFailingRate());
        Files.delete(directory.resolve("stage-completion.json"));
        assertTrue(Results.compare(directory, directory).deltas().isEmpty());
        assertEquals("INVALID", Results.summary(directory).eligibility().outcome());
        assertTrue(ThroughputCharts.data(List.of(Results.summary(directory))).runs().getFirst().curves().getFirst().phases().isEmpty());
        assertNull(ThroughputCharts.data(List.of(Results.summary(directory))).runs().getFirst().bounds().getFirst().passing());
        Files.writeString(directory.resolve("run.json"), "{\"state\":\"FAILED\"}");
        JSON.writeValue(directory.resolve("stage-result.json").toFile(), plan.invalid("Stage execution failed"));
        assertEquals("Stage execution failed", Results.summary(directory).eligibility().reason());
        assertTrue(Results.compare(directory, directory).deltas().isEmpty());
    }
}
