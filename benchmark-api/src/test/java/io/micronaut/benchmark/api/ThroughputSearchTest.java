package io.micronaut.benchmark.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ThroughputSearchTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    final ThroughputStage plan = new ThroughputStage("validation", 1000, List.of(
            new ThroughputStage.Phase("main/0", 100, 1000),
            new ThroughputStage.Phase("main/1", 110, 1000),
            new ThroughputStage.Phase("main/2", 121, 1000)));

    @Test
    void deliveredRateMustSupportTheClaimedRateEvenWithPerfectResponses() {
        var measured = new ThroughputStage("validation", 1000, List.of(
                new ThroughputStage.Phase("main/0", 75000, 45000),
                new ThroughputStage.Phase("main/1", 300000, 45000),
                new ThroughputStage.Phase("main/2", 330000, 45000)));
        // Actual request count from the thorough Pure Netty run, with every request completed.
        var phases = List.of(phase("main/0", 45000, 3375000, 3375000),
                phase("main/1", 45000, 5779328, 5779328), phase("main/2", 45000, 14850000, 14850000));
        var result = measured.evaluate(stats(null, null, phases));
        assertEquals("GENERATOR_LIMITED", result.outcome());
        assertEquals(75000, result.highestPassingRate());
        assertNull(result.firstFailingRate());
        assertFalse(result.canValidate());
        assertEquals(List.of("PASS", "GENERATOR_LIMITED", "EXCLUDED"), result.phases().stream().map(ThroughputStage.Observation::status).toList());
        assertTrue(result.reason().contains("5779328 of 13500000.0"));
        // An independently reported overload remains a SUT failure boundary.
        assertEquals("BRACKETED", measured.evaluate(stats("main/1", "Response time exceeded", phases)).outcome());
    }

    @Test
    void deliveredRateUsesInjectionTimeRampAverageAndPoissonCountTolerance() {
        assertNull(ThroughputStage.deliveryShortfallReason(990000, 100000, 10000));
        assertNotNull(ThroughputStage.deliveryShortfallReason(989999, 100000, 10000));
        assertNull(ThroughputStage.deliveryShortfallReason(9700, 1000, 10000));
        assertNotNull(ThroughputStage.deliveryShortfallReason(9400, 1000, 10000));
        assertNull(ThroughputStage.deliveryShortfallReason(1, 1, 1000));

        var ramp = new ThroughputStage("discovery", 1000, List.of(
                new ThroughputStage.Phase("main/0", 1000, 10000),
                new ThroughputStage.Phase("main/1", 10000, 10000)), 5000L);
        var phases = new java.util.ArrayList<>(List.of(phase("main/0", 10000, 10000, 10000),
                phase("ramp/1", 5000, 27500, 27500), phase("main/1", 120000, 100000, 100000)));
        assertEquals("LOWER_BOUND", ramp.evaluate(stats(null, null, phases)).outcome());
        phases.set(1, phase("ramp/1", 5000, 20000, 20000));
        var result = ramp.evaluate(stats(null, null, phases));
        assertEquals("GENERATOR_LIMITED", result.outcome());
        assertEquals(1000, result.highestPassingRate());
        assertNull(result.firstFailingRate());
        assertEquals("EXCLUDED", result.phases().getLast().status());
    }

    @Test
    void requiredPreflightMustCompleteBothRequestsBeforeAnyRateCanPass() {
        var checked = new ThroughputStage(plan.stage(), plan.warmupMillis(), plan.phases(), plan.rampMillis(), 2);
        var measurements = plan.phases().stream().map(p -> phase(p.name(), 1000, 100)).toList();
        assertEquals("INVALID", checked.evaluate(stats(null, null, measurements)).outcome());
        for (int responses : List.of(0, 1, 3)) {
            var incomplete = new java.util.ArrayList<>(measurements);
            incomplete.add(phase("preflight", 1, 2, responses));
            var result = checked.evaluate(stats(null, null, incomplete));
            assertEquals("INVALID", result.outcome());
            assertNull(result.highestPassingRate());
        }
        var complete = new java.util.ArrayList<>(measurements);
        complete.add(phase("preflight", 0, 2, 2));
        assertEquals("LOWER_BOUND", checked.evaluate(stats(null, null, complete)).outcome());
        assertEquals("INVALID", checked.evaluate(stats("preflight", "Invalid response ratio exceeded", complete)).outcome());
        assertEquals("INVALID", checked.evaluate(stats(null, null, complete),
                new ThroughputStage.Completion(true, false, List.of("warmup", "main/0", "main/1", "main/2"))).outcome());
        var broken = JSON.valueToTree(complete.getLast());
        ((tools.jackson.databind.node.ObjectNode) broken.path("total").path("summary")).put("invalid", 1);
        complete.set(complete.size() - 1, JSON.treeToValue(broken, BenchmarkStats.Stats.class));
        assertEquals("INVALID", checked.evaluate(stats(null, null, complete)).outcome());
        assertEquals(checked, JSON.readValue(JSON.writeValueAsString(checked), ThroughputStage.class));
        assertEquals(0, JSON.readValue("""
                {"stage":"validation","warmupMillis":1000,"phases":[{"name":"main/0","rate":100,"durationMillis":1000}]}
                """, ThroughputStage.class).preflightRequests());
    }

    static BenchmarkStats.Stats phase(String name, long duration, int responses) {
        return phase(name, duration, 100, responses);
    }

    static BenchmarkStats.Stats phase(String name, long duration, int requests, int responses) {
        return JSON.readValue("""
                {"name":"%s","phase":"%s","total":{"summary":{"startTime":1000,"endTime":%d,
                  "requestCount":%d,"responseCount":%d,"percentileResponseTime":{},"extensions":{},
                  "minResponseTime":0,"maxResponseTime":0,"meanResponseTime":0,"stdDevResponseTime":0,
                  "invalid":0,"connectionErrors":0,"requestTimeouts":0,"internalErrors":0,"blockedTime":0}}}
                """.formatted(name, name, 1000 + duration, requests, responses), BenchmarkStats.Stats.class);
    }

    BenchmarkStats stats(String failed, String message, List<BenchmarkStats.Stats> phases) {
        var all = new java.util.ArrayList<BenchmarkStats.Stats>();
        all.add(phase("warmup", 1000, 100));
        all.addAll(phases);
        return new BenchmarkStats(new BenchmarkStats.Info(List.of()), failed == null ? List.of()
                : List.of(new BenchmarkStats.SlaFailure(failed, message)), all);
    }

    @Test
    void delayedFailuresExcludeLaterPassingPhasesRegardlessOfStatisticsOrder() {
        var stats = stats("main/1", "Response time exceeded", List.of(
                phase("main/2", 1000, 100), phase("main/0", 1000, 100), phase("main/1", 1000, 100)));
        var result = plan.evaluate(stats);
        assertEquals("BRACKETED", result.outcome());
        assertEquals(100, result.highestPassingRate());
        assertEquals(110, result.firstFailingRate());
        assertEquals(List.of("PASS", "FAIL", "EXCLUDED"), result.phases().stream().map(ThroughputStage.Observation::status).toList());
        assertFalse(result.eligible("main/2"));
    }

    @Test
    void cannotSkipMissingShortOrUndrainedPhases() {
        for (var phases : List.of(
                List.of(phase("main/0", 1000, 100), phase("main/2", 1000, 100)),
                List.of(phase("main/0", 1000, 100), phase("main/1", 999, 100), phase("main/2", 1000, 100)),
                List.of(phase("main/0", 1000, 100), phase("main/1", 1000, 99), phase("main/2", 1000, 100)))) {
            var result = plan.evaluate(stats(null, null, phases));
            assertEquals("INVALID", result.outcome());
            assertEquals(100, result.highestPassingRate());
            assertNull(result.firstFailingRate());
            assertFalse(result.eligible("main/2"));
        }
        var result = plan.evaluate(stats(null, null, List.of(phase("main/0", 1000, 100))),
                new ThroughputStage.Completion(true, false, List.of("warmup")));
        assertNull(result.highestPassingRate());
    }

    @Test
    void toleratesMinorResponseOvercountsInCompletedMeasurements() {
        // Counts from the completed Micronaut Loom validation phase: 26 extra responses.
        for (var measurement : List.of(phase("main/1", 15013, 833207, 833233),
                phase("main/1", 1000, 1000, 1001))) {
            var result = plan.evaluate(stats(null, null, List.of(
                    phase("main/0", 1000, 100), measurement, phase("main/2", 1000, 100))),
                    new ThroughputStage.Completion(true, false, List.of("warmup", "main/0", "main/1", "main/2")));
            assertEquals("LOWER_BOUND", result.outcome());
            assertEquals(121, result.highestPassingRate());
            assertTrue(result.eligible("main/1"));
        }
    }

    @Test
    void responseOvercountToleranceDoesNotHideIncompleteOrCorruptMeasurements() {
        for (var measurement : List.of(phase("main/1", 1000, 1000, 1002),
                phase("main/1", 1000, 1000, 999), phase("main/1", 999, 1000, 1001),
                phase("main/1", 1000, 0, 0), phase("main/1", 1000, 0, 1))) {
            var result = plan.evaluate(stats(null, null, List.of(
                    phase("main/0", 1000, 100), measurement, phase("main/2", 1000, 100))));
            assertEquals("INVALID", result.outcome());
            assertEquals(100, result.highestPassingRate());
            assertNull(result.firstFailingRate());
            assertFalse(result.eligible("main/2"));
        }
    }

    @Test
    void distinguishesGeneratorLimitsFromSutBoundariesAndFirstFailure() {
        for (String message : List.of("Progress was blocked waiting for a free connection")) {
            var result = plan.evaluate(stats("main/1", message, List.of(phase("main/0", 1000, 100), phase("main/1", 1000, 100))));
            assertEquals("GENERATOR_LIMITED", result.outcome());
            assertEquals(100, result.highestPassingRate());
            assertNull(result.firstFailingRate());
            assertFalse(result.canValidate());
        }
        var first = plan.evaluate(stats("main/0", "Response time exceeded", List.of(phase("main/0", 1000, 100))));
        assertEquals("INCONCLUSIVE", first.outcome());
        assertNull(first.highestPassingRate());
        assertEquals(100, first.firstFailingRate());
    }

    @Test
    void sessionFailureEstablishesTheSameCutoffAsAnSlaFailure() {
        var discovery = new ThroughputStage("discovery", plan.warmupMillis(), plan.phases());
        var good = stats(null, null, plan.phases().stream().map(p -> phase(p.name(), 1000, 100)).toList());
        var search = new ThroughputSearch("quick", 100, 1000, "1s", "1s", "1s", 25, 5, 1, 2);
        var completion = new ThroughputStage.Completion(true, false, List.of("warmup", "main/0", "main/1", "main/2"));
        for (String sla : List.of("", "Response time exceeded", "Error ratio exceeded", "Invalid response ratio exceeded")) {
            var failures = new java.util.ArrayList<BenchmarkStats.SlaFailure>();
            failures.add(new BenchmarkStats.SlaFailure("main/1", "Exceeded session limit"));
            if (!sla.isEmpty()) failures.add(new BenchmarkStats.SlaFailure("main/1", sla));
            var mixed = new BenchmarkStats(good.info(), failures, good.stats());
            var result = discovery.evaluate(mixed, completion);
            assertEquals("BRACKETED", result.outcome());
            assertEquals(List.of("PASS", "FAIL", "EXCLUDED"), result.phases().stream().map(ThroughputStage.Observation::status).toList());
            assertEquals(110, result.firstFailingRate());
            assertTrue(result.canValidate());
            assertFalse(result.eligible("main/1"));
            assertEquals(List.of(25, 50, 75, 90), search.validation(result).phases().stream().limit(4).map(ThroughputStage.Phase::rate).toList());
            assertEquals(138, search.validation(result).phases().getLast().rate());
            assertFalse(discovery.evaluate(mixed, new ThroughputStage.Completion(true, false, List.of("warmup", "main/0"))).canValidate());
            assertFalse(discovery.evaluate(mixed, new ThroughputStage.Completion(true, true, completion.terminatedPhases())).canValidate());
            var validation = plan.evaluate(mixed, completion);
            assertEquals(100, new ThroughputResult(search, List.of(new ThroughputResult.Repetition(1, "d", result, "v", validation))).aggregate().medianPassingRate());

            var missing = new BenchmarkStats(good.info(), mixed.failures(), good.stats().stream().filter(p -> !p.name().equals("main/1")).toList());
            assertFalse(discovery.evaluate(missing, completion).canValidate());
            var undrained = stats(null, null, List.of(phase("main/0", 1000, 100), phase("main/1", 1000, 99)));
            assertFalse(discovery.evaluate(new BenchmarkStats(good.info(), mixed.failures(), undrained.stats()), completion).canValidate());
        }
        var first = new BenchmarkStats(good.info(), List.of(new BenchmarkStats.SlaFailure("main/0", "Exceeded session limit"),
                new BenchmarkStats.SlaFailure("main/0", "Response time exceeded")), good.stats());
        assertFalse(discovery.evaluate(first, completion).canValidate());
        var blockedOnly = new BenchmarkStats(good.info(), List.of(new BenchmarkStats.SlaFailure("main/1", "Exceeded session limit"),
                new BenchmarkStats.SlaFailure("main/1", "Progress was blocked waiting for a free connection")), good.stats());
        assertTrue(discovery.evaluate(blockedOnly, completion).canValidate());
        assertEquals(110, discovery.evaluate(blockedOnly, completion).firstFailingRate());
        var slaAndBlocking = new BenchmarkStats(good.info(), List.of(new BenchmarkStats.SlaFailure("main/1", "Progress was blocked waiting for a free connection"),
                new BenchmarkStats.SlaFailure("main/1", "Response time exceeded")), good.stats());
        assertTrue(discovery.evaluate(slaAndBlocking, completion).canValidate());
        assertEquals(110, discovery.evaluate(slaAndBlocking, completion).firstFailingRate());
        assertEquals("BRACKETED", discovery.evaluate(slaAndBlocking, completion).outcome());
        var internal = JSON.valueToTree(good.stats().get(2));
        ((tools.jackson.databind.node.ObjectNode) internal.path("total").path("summary")).put("internalErrors", 1);
        var broken = new java.util.ArrayList<>(good.stats());
        broken.set(2, JSON.treeToValue(internal, BenchmarkStats.Stats.class));
        assertFalse(discovery.evaluate(new BenchmarkStats(good.info(), slaAndBlocking.failures(), broken), completion).canValidate());
    }

    @Test
    void boundsAreNotInventedAndRequestErrorsFail() {
        var phases = new java.util.ArrayList<>(plan.phases().stream().map(p -> phase(p.name(), 1000, 100)).toList());
        assertEquals("LOWER_BOUND", plan.evaluate(stats(null, null, phases)).outcome());
        var node = JSON.valueToTree(phases.get(1));
        ((tools.jackson.databind.node.ObjectNode) node.path("total").path("summary")).put("invalid", 1);
        phases.set(1, JSON.treeToValue(node, BenchmarkStats.Stats.class));
        assertEquals("BRACKETED", plan.evaluate(stats(null, null, phases)).outcome());
    }

    @Test
    void internalGeneratorErrorsCannotEstablishASutBoundary() {
        var broken = JSON.valueToTree(phase("main/1", 1000, 100));
        ((tools.jackson.databind.node.ObjectNode) broken.path("total").path("summary")).put("internalErrors", 1);
        var result = plan.evaluate(stats("main/1", "Error ratio exceeded", List.of(
                phase("main/0", 1000, 100), JSON.treeToValue(broken, BenchmarkStats.Stats.class), phase("main/2", 1000, 100))));
        assertEquals("INVALID", result.outcome());
        assertEquals(100, result.highestPassingRate());
        assertNull(result.firstFailingRate());
        assertFalse(result.eligible("main/2"));
    }

    @Test
    void cancellationAndInfrastructureErrorsInvalidateObservationsAndMissingStatsCannotSupplyABoundary() {
        var good = stats(null, null, plan.phases().stream().map(p -> phase(p.name(), 1000, 100)).toList());
        for (var completion : List.of(new ThroughputStage.Completion(false, false, List.of()),
                new ThroughputStage.Completion(true, true, List.of("warmup", "main/0", "main/1", "main/2")))) {
            var result = plan.evaluate(good, completion);
            assertEquals("INVALID", result.outcome());
            assertNull(result.highestPassingRate());
        }
        var infrastructureError = new BenchmarkStats(new BenchmarkStats.Info(List.of(new BenchmarkStats.Info.Error("agent0", "Disconnected"))),
                good.failures(), good.stats());
        assertNull(plan.evaluate(infrastructureError).highestPassingRate());
        var missing = stats("main/1", "Response time exceeded", List.of(phase("main/0", 1000, 100), phase("main/2", 1000, 100)));
        assertEquals("INVALID", plan.evaluate(missing).outcome());
        assertEquals(100, plan.evaluate(missing).highestPassingRate());
        assertNull(plan.evaluate(missing).firstFailingRate());
    }

    @Test
    void roundsUpMakesProgressAndIncludesExactEndpoint() {
        assertEquals(List.of(1, 2, 3), ThroughputSearch.rates(1, 3, 2));
        assertEquals(List.of(100, 125, 157, 160), ThroughputSearch.rates(100, 160, 25));
        assertEquals(List.of(100), ThroughputSearch.rates(100, 100, 25));
        assertThrows(IllegalArgumentException.class, () -> ThroughputSearch.rates(100, 200, 0));
        assertThrows(IllegalArgumentException.class, () -> ThroughputSearch.rates(1, 1_000_000, 0.001));
    }

    @Test
    void discoveryRampsKeepMeasurementDurationsAndHistoricalPlans() {
        var search = new ThroughputSearch("thorough", 100, 125, "180s", "15s", "45s", 25, 2, 2, 2, "5s");
        var discovery = search.discovery();
        assertEquals(List.of("main/0", "ramp/1", "main/1"),
                discovery.executionPhases().stream().map(ThroughputStage.Phase::name).toList());
        assertEquals(List.of(15000L, 5000L, 15000L),
                discovery.executionPhases().stream().map(ThroughputStage.Phase::durationMillis).toList());
        assertEquals(0, search.validation(new ThroughputStage.Result("discovery", "BRACKETED", 100, 125, null, List.of())).rampMillis());
        assertEquals(search, JSON.readValue(JSON.writeValueAsString(search), ThroughputSearch.class));
        assertEquals(discovery, JSON.readValue(JSON.writeValueAsString(discovery), ThroughputStage.class));

        var savedSearch = (tools.jackson.databind.node.ObjectNode) JSON.valueToTree(search);
        savedSearch.remove("discoveryRampDuration");
        var historical = JSON.treeToValue(savedSearch, ThroughputSearch.class);
        assertEquals("0s", historical.discoveryRampDuration());
        assertEquals(historical.discovery().phases(), historical.discovery().executionPhases());
        var savedPlan = (tools.jackson.databind.node.ObjectNode) JSON.valueToTree(discovery);
        savedPlan.remove("rampMillis");
        var historicalPlan = JSON.treeToValue(savedPlan, ThroughputStage.class);
        assertEquals(historicalPlan.phases(), historicalPlan.executionPhases());
    }

    @Test
    void aPassingRampDoesNotReplaceAMissingOrIncompleteMeasurement() {
        var discovery = new ThroughputSearch("quick", 100, 125, "1s", "1s", "1s", 25, 5, 1, 2, "2s").discovery();
        var points = List.of(phase("main/0", 1000, 100), phase("ramp/1", 2000, 225, 225), phase("main/1", 999, 125, 125));
        var result = discovery.evaluate(stats(null, null, points));
        assertEquals("INVALID", result.outcome());
        assertEquals(100, result.highestPassingRate());
        assertEquals("RAMP_PASS", result.phases().get(1).status());
        assertFalse(result.eligible("ramp/1"));
        result = discovery.evaluate(stats(null, null, points.subList(0, 2)));
        assertEquals("INVALID", result.outcome());
        assertEquals(100, result.highestPassingRate());

        // A missing transition cannot be skipped even if the next measurement passed.
        result = discovery.evaluate(stats(null, null, List.of(phase("main/0", 1000, 100), phase("main/1", 1000, 100))));
        assertEquals("INVALID", result.outcome());
        assertEquals(100, result.highestPassingRate());
        assertFalse(result.eligible("main/1"));
    }

    @Test
    void delayedRampFailureCutsOffLaterMeasurementsAtTheRampTarget() {
        var discovery = new ThroughputSearch("quick", 100, 125, "1s", "1s", "1s", 25, 5, 1, 2, "2s").discovery();
        var points = List.of(phase("main/1", 1000, 125, 125), phase("ramp/1", 2000, 225, 225), phase("main/0", 1000, 100));
        var completion = new ThroughputStage.Completion(true, false, List.of("warmup", "main/0", "ramp/1", "main/1"));
        for (String failure : List.of("Response time exceeded", "Exceeded session limit")) {
            var result = discovery.evaluate(stats("ramp/1", failure, points), completion);
            assertEquals("BRACKETED", result.outcome());
            assertEquals(100, result.highestPassingRate());
            assertEquals(125, result.firstFailingRate());
            assertEquals(List.of("PASS", "RAMP_FAIL", "EXCLUDED"), result.phases().stream().map(ThroughputStage.Observation::status).toList());
            assertTrue(result.canValidate());
            assertFalse(result.eligible("ramp/1"));
            assertFalse(result.eligible("main/1"));
        }
        var result = discovery.evaluate(stats(null, null, points), completion);
        assertEquals("LOWER_BOUND", result.outcome());
        assertEquals(125, result.highestPassingRate());
        assertEquals(List.of("PASS", "RAMP_PASS", "PASS"), result.phases().stream().map(ThroughputStage.Observation::status).toList());
    }

    @Test
    void validationStartsBelowDiscoveryAndAggregationRequiresEveryRepetition() {
        var search = new ThroughputSearch("thorough", 100, 1000, "180s", "15s", "45s", 25, 2, 2, 2);
        var discovery = new ThroughputStage.Result("discovery", "BRACKETED", 100, 125, null, List.of());
        var validationPlan = search.validation(discovery);
        assertEquals(List.of(25, 50, 75, 90), validationPlan.phases().stream().limit(4).map(ThroughputStage.Phase::rate).toList());
        assertEquals(157, validationPlan.phases().getLast().rate());
        assertEquals(45000, validationPlan.phases().getFirst().durationMillis());
        var first = new ThroughputResult.Repetition(1, "1/discovery", discovery, "1/validation", discovery);
        assertNull(new ThroughputResult(search, List.of(first)).aggregate());
        var second = new ThroughputResult.Repetition(2, "2/discovery", discovery, "2/validation",
                new ThroughputStage.Result("validation", "BRACKETED", 110, 130, null, List.of()));
        assertEquals(105, new ThroughputResult(search, List.of(first, second)).aggregate().medianPassingRate());
        second = new ThroughputResult.Repetition(2, "2/discovery", discovery, null, null);
        assertNull(new ThroughputResult(search, List.of(first, second)).aggregate());
    }

    @Test
    void validationRampUsesMeasuredPhaseDurationsAndPreservesFineSweep() {
        var discovery = new ThroughputStage.Result("discovery", "BRACKETED", 108560, 135700, null, List.of());
        for (String preset : List.of("quick", "thorough")) {
            boolean quick = preset.equals("quick");
            var search = new ThroughputSearch(preset, 1000, 200000, quick ? "60s" : "180s",
                    quick ? "10s" : "15s", quick ? "15s" : "45s", 25, quick ? 5 : 2, quick ? 1 : 2, 2);
            var validation = search.validation(discovery);
            var rates = validation.phases().stream().map(ThroughputStage.Phase::rate).toList();
            assertEquals(List.of(27140, 54280, 81420, 97704), rates.subList(0, 4));
            assertEquals(ThroughputSearch.rates(97704, 169625, search.validationStep()), rates.subList(3, rates.size()));
            assertTrue(validation.phases().stream().allMatch(p -> p.durationMillis() == (quick ? 15000 : 45000)));
            assertEquals(search, JSON.readValue(JSON.writeValueAsString(search), ThroughputSearch.class));
        }
    }

    @Test
    void validationCanBracketAboveTheDiscoveryFailure() {
        var search = new ThroughputSearch("quick", 1000, 300000, "1s", "1s", "1s", 25, 5, 1, 2);
        var discovery = new ThroughputStage.Result("discovery", "BRACKETED", 44465, 55582, null, List.of());
        var validation = search.validation(discovery);
        var last = validation.phases().getLast();
        assertEquals(69478, last.rate());
        var measurements = validation.phases().stream().map(p -> phase(p.name(), 1000, p.rate(), p.rate())).toList();
        var result = validation.evaluate(stats(last.name(), "Response time exceeded", measurements));
        assertEquals("BRACKETED", result.outcome());
        assertEquals(68452, result.highestPassingRate());
        assertEquals(69478, result.firstFailingRate());
    }

    @Test
    void validationHeadroomRespectsTheCeilingWithoutOverflow() {
        var search = new ThroughputSearch("quick", 1000, 300000, "1s", "1s", "1s", 25, 5, 1, 2);
        var discovery = new ThroughputStage.Result("discovery", "BRACKETED", 212032, 265040, null, List.of());
        assertEquals(300000, search.validation(discovery).phases().getLast().rate());

        search = new ThroughputSearch("quick", 1000, Integer.MAX_VALUE, "1s", "1s", "1s", 25, 5, 1, 1);
        discovery = new ThroughputStage.Result("discovery", "BRACKETED", 1600000000, 2000000000, null, List.of());
        assertEquals(Integer.MAX_VALUE, search.validation(discovery).phases().getLast().rate());
    }

    @Test
    void validationRampRoundsUpAndDeduplicatesLowRatesAndCeiling() {
        var search = new ThroughputSearch("quick", 1, 4, "1s", "1s", "1s", 25, 5, 1, 2);
        var discovery = new ThroughputStage.Result("discovery", "BRACKETED", 3, 4, null, List.of());
        assertEquals(List.of(1, 2, 3, 4), search.validation(discovery).phases().stream().map(ThroughputStage.Phase::rate).toList());
        search = new ThroughputSearch("quick", 1, 1, "1s", "1s", "1s", 25, 5, 1, 2);
        discovery = new ThroughputStage.Result("discovery", "LOWER_BOUND", 1, null, null, List.of());
        assertEquals(List.of(1), search.validation(discovery).phases().stream().map(ThroughputStage.Phase::rate).toList());
    }

    @Test
    void validationRampFailureExcludesEveryLaterPhaseIncludingFineSweep() {
        var search = new ThroughputSearch("quick", 100, 125, "1s", "1s", "1s", 25, 5, 1, 2);
        var validation = search.validation(new ThroughputStage.Result("discovery", "BRACKETED", 100, 125, null, List.of()));
        var measurements = validation.phases().stream().map(p -> phase(p.name(), 1000, 100)).toList();
        var result = validation.evaluate(stats("main/1", "Response time exceeded", measurements));
        assertEquals("BRACKETED", result.outcome());
        assertEquals(25, result.highestPassingRate());
        assertEquals(50, result.firstFailingRate());
        assertTrue(result.phases().stream().skip(2).allMatch(p -> p.status().equals("EXCLUDED")));
        assertEquals("INCONCLUSIVE", validation.evaluate(stats("main/0", "Response time exceeded", measurements)).outcome());
    }
}
