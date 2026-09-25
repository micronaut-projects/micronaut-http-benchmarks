package io.micronaut.benchmark.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The planned order, rather than arrival order of statistics, determines the eligible prefix. */
public record ThroughputStage(String stage, long warmupMillis, List<Phase> phases) {
    public ThroughputStage {
        if (!List.of("discovery", "validation").contains(stage) || warmupMillis <= 0 || phases.isEmpty()) {
            throw new IllegalArgumentException("Invalid throughput stage");
        }
        phases = List.copyOf(phases);
        int previous = 0;
        for (int i = 0; i < phases.size(); i++) {
            Phase p = phases.get(i);
            if (!p.name().equals("main/" + i) || p.rate() <= previous || p.durationMillis() <= 0) {
                throw new IllegalArgumentException("Stage phases must be complete, ordered and strictly ascending");
            }
            previous = p.rate();
        }
    }

    public record Phase(String name, int rate, long durationMillis) { }
    public record Completion(boolean completed, boolean cancelled, List<String> terminatedPhases) { }
    public record Observation(String name, int rate, String status, String reason) {
        public boolean eligible() { return "PASS".equals(status); }
    }
    public record Result(String stage, String outcome, Integer highestPassingRate, Integer firstFailingRate,
                         String reason, List<Observation> phases) {
        public boolean eligible(String name) {
            return phases.stream().anyMatch(p -> p.name().equals(name) && p.eligible());
        }

        public boolean canValidate() {
            return highestPassingRate != null && List.of("BRACKETED", "LOWER_BOUND").contains(outcome);
        }
    }

    public Result evaluate(BenchmarkStats stats) {
        return evaluate(stats, null);
    }

    public Result invalid(String reason) {
        return new Result(stage, "INVALID", null, null, reason, phases.stream()
                .map(p -> new Observation(p.name(), p.rate(), "EXCLUDED", reason)).toList());
    }

    public Result evaluate(BenchmarkStats stats, Completion completion) {
        String invalid = null;
        if (stats.info() == null || stats.info().errors() == null || stats.stats() == null || stats.failures() == null) {
            invalid = "Missing final statistics";
        } else if (Boolean.TRUE.equals(stats.info().cancelled()) || completion != null && (!completion.completed() || completion.cancelled())) {
            invalid = "Hyperfoil run was cancelled or did not complete";
        } else if (!stats.info().errors().isEmpty()) {
            invalid = "Hyperfoil errors: " + stats.info().errors();
        } else {
            var warmup = stats.findPhase("warmup");
            if (!complete(warmup, warmupMillis) || completion != null && !completion.terminatedPhases().contains("warmup") || requestErrors(warmup)
                    || stats.failures().stream().anyMatch(f -> f.phase().equals("warmup"))) {
                invalid = "Warmup failed or is incomplete";
            }
            if (stats.failures().stream().anyMatch(f -> !f.phase().equals("warmup") && phases.stream().noneMatch(p -> p.name().equals(f.phase())))) {
                invalid = "Failure for an unknown phase";
            }
            if (stats.stats().stream().map(BenchmarkStats.Stats::name).distinct().count() != stats.stats().size()) {
                invalid = "Multiple metrics per phase are not supported by this throughput workload";
            }
        }
        var observations = new ArrayList<Observation>();
        Integer passing = null, failing = null;
        String outcome = invalid == null ? "LOWER_BOUND" : "INVALID";
        String reason = invalid;
        boolean cutoff = invalid != null;
        for (Phase planned : phases) {
            if (cutoff) {
                observations.add(new Observation(planned.name(), planned.rate(), "EXCLUDED", reason));
                continue;
            }
            var actual = stats.findPhase(planned.name());
            var failures = stats.failures().stream().filter(f -> f.phase().equals(planned.name())).toList();
            boolean sessionLimit = failures.stream().anyMatch(f -> f.message().toLowerCase(Locale.ROOT).contains("session limit"));
            boolean limited = failures.stream().anyMatch(f -> generatorLimit(f.message()))
                    || actual != null && actual.total() != null && actual.total().summary() != null && actual.total().summary().blockedTime > 0;
            String status;
            if (actual == null || actual.total() == null || actual.total().summary() == null
                    || completion != null && !completion.terminatedPhases().contains(planned.name())) {
                outcome = "INVALID";
                status = "INCOMPLETE";
                reason = "Missing finalized measurement statistics";
            } else if (actual.total().summary().internalErrors > 0) {
                outcome = "INVALID";
                status = "INVALID";
                reason = "Load generator response processing failed";
            } else if (sessionLimit) {
                var summary = actual.total().summary();
                if (summary.responseCount == 0 || summary.requestCount > (long) summary.responseCount
                        + summary.connectionErrors + summary.requestTimeouts) {
                    outcome = "INVALID";
                    status = "INCOMPLETE";
                    reason = "Missing or undrained measurements at session-limit failure";
                } else {
                    // Exhaustion can be the first sign of overload; do not require a second SLA report.
                    failing = planned.rate();
                    outcome = passing == null ? "INCONCLUSIVE" : "BRACKETED";
                    status = "FAIL";
                    reason = "Session limit exceeded: " + failures;
                }
            } else if (failures.stream().anyMatch(f -> !generatorLimit(f.message())) || requestErrors(actual)) {
                // Connection queueing must not mask an independently reported SLA/request failure.
                failing = planned.rate();
                outcome = passing == null ? "INCONCLUSIVE" : "BRACKETED";
                status = "FAIL";
                reason = failures.isEmpty() ? "Request failures" : failures.toString();
            } else if (limited) {
                outcome = "GENERATOR_LIMITED";
                status = "GENERATOR_LIMITED";
                reason = "Load generator limited this phase: " + failures;
            } else if (!complete(actual, planned.durationMillis())) {
                outcome = "INVALID";
                status = "INCOMPLETE";
                reason = "Missing or incomplete measurement phase";
            } else {
                passing = planned.rate();
                observations.add(new Observation(planned.name(), planned.rate(), "PASS", null));
                continue;
            }
            observations.add(new Observation(planned.name(), planned.rate(), status, reason));
            reason = "Cutoff at " + planned.name() + ": " + reason;
            cutoff = true;
        }
        return new Result(stage, outcome, passing, failing, reason, List.copyOf(observations));
    }

    private static boolean generatorLimit(String message) {
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("session limit") || m.contains("free connection") || m.contains("blocked");
    }

    private static boolean requestErrors(BenchmarkStats.Stats phase) {
        if (phase == null || phase.total() == null || phase.total().summary() == null) return false;
        var s = phase.total().summary();
        return s.invalid > 0 || s.requestTimeouts > 0 || s.connectionErrors > 0 || s.internalErrors > 0;
    }

    private static boolean complete(BenchmarkStats.Stats phase, long duration) {
        if (phase == null || phase.total() == null || phase.total().summary() == null) return false;
        var s = phase.total().summary();
        // Allow up to 0.1% extra responses for minor Hyperfoil accounting inconsistencies.
        // Still require the full duration and reject missing responses or empty measurements.
        return s.startTime > 0 && s.endTime - s.startTime >= duration && s.requestCount > 0
                && s.responseCount >= s.requestCount
                && (long) s.responseCount - s.requestCount <= s.requestCount / 1000;
    }
}
