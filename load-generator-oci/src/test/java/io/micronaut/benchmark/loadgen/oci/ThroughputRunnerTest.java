package io.micronaut.benchmark.loadgen.oci;

import io.hyperfoil.api.config.BenchmarkData;
import io.hyperfoil.core.parser.BenchmarkParser;
import io.micronaut.benchmark.api.BenchmarkStats;
import io.micronaut.benchmark.api.ThroughputSearch;
import io.micronaut.benchmark.api.ThroughputStage;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThroughputRunnerTest {
    private static final String TEMPLATE = """
            name: adaptive
            agents: {}
            failurePolicy: CANCEL
            http:
              host: http://localhost:8080
            phases:
            - preflight:
                atOnce:
                  users: 2
                  maxDuration: 2m
                  isWarmup: true
                  scenario:
                  - test:
                    - httpRequest:
                        GET: /
                        handler:
                          autoRangeCheck: true
                        sla:
                        - errorRatio: 0
                          invalidRatio: 0
            - warmup:
                always:
                  duration: 180s
                  users: 200
                  isWarmup: true
                  startAfterStrict: preflight
                  scenario:
                  - test:
                    - httpRequest:
                        GET: /
            - main/0:
                constantRate:
                  duration: 45s
                  usersPerSec: 1000
                  maxSessions: 2000
                  startAfterStrict: warmup
                  scenario:
                  - test:
                    - httpRequest:
                        GET: /
                        handler:
                          autoRangeCheck: true
                        sla:
                        - limits: { '0.50': 100ms, '0.95': 200ms, '0.99': 1000ms }
                          blockedRatio: 1
                        - errorRatio: 0
                          invalidRatio: 0
                          blockedRatio: 0
            """;

    @Test
    void fullDiscoverySurvivesYamlRewritingAndHyperfoilParsing() throws Exception {
        assertLargeSweepParses("discovery");
    }

    @Test
    void denseValidationSurvivesYamlRewritingAndHyperfoilParsing() throws Exception {
        assertLargeSweepParses("validation");
    }

    @Test
    void fixedRateRunRejectsUnderDeliveryAndIgnoresClosedLoopWarmup() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        String json = """
                {"info":{"errors":[]},"failures":[],"stats":[
                  {"name":"main/0","phase":"main","total":{"summary":{
                    "startTime":1000,"endTime":46000,"minResponseTime":0,"maxResponseTime":0,
                    "meanResponseTime":0,"stdDevResponseTime":0,"invalid":0,"connectionErrors":0,
                    "requestTimeouts":0,"internalErrors":0,"blockedTime":0,
                    "requestCount":20000,"responseCount":20000,"percentileResponseTime":{},"extensions":{}}}}
                ]}
                """;
        var stats = mapper.readValue(json, BenchmarkStats.class);
        var failures = HyperfoilRunner.deliveryFailures(TEMPLATE, stats);
        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().contains("main/0: Offered load not reached"));
        assertTrue(HyperfoilRunner.deliveryFailures(TEMPLATE.replace("usersPerSec: 1000", "usersPerSec: 400"), stats).isEmpty());
        // An SLA failure cancels the run: the failing phase is cut short and later phases have no statistics.
        var overloaded = mapper.readValue(json.replace("\"failures\":[]",
                "\"failures\":[{\"phase\":\"main/0\",\"message\":\"Response time exceeded\"}]"), BenchmarkStats.class);
        assertTrue(HyperfoilRunner.deliveryFailures(TEMPLATE, overloaded).isEmpty());
    }

    private void assertLargeSweepParses(String stage) throws Exception {
        // Default discovery has 53 phases including ramps; a finer validation sweep also exceeds 50.
        var search = new ThroughputSearch("thorough", 1000, 300000, "180s", "15s", "45s",
                25, 0.5, 2, 2, "5s");
        var plan = ThroughputRunner.planForTemplate(TEMPLATE, stage.equals("discovery") ? search.discovery() : search.validation(
                new ThroughputStage.Result("discovery", "BRACKETED", 100000, 125000, null, List.of())));
        assertEquals(2, plan.preflightRequests());
        assertTrue(plan.executionPhases().size() > 50);
        String workload = ThroughputRunner.workload(TEMPLATE, search, plan);

        // The runner loads and rewrites the saved workload before submitting it to Hyperfoil.
        // Keep the default alias limit used there and by offline analysis.
        var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> definition = yaml.load(workload);
        String rewritten = yaml.dump(definition);
        assertEquals(definition, yaml.load(rewritten));
        var parser = BenchmarkParser.instance();
        var benchmark = parser.buildBenchmark(parser.createSource(rewritten, BenchmarkData.EMPTY), Map.of());
        assertEquals(plan.executionPhases().size() + 2, benchmark.phases().size());
    }
}
