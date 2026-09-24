package io.micronaut.benchmark.api;

import java.util.List;

/** Incrementally saved; unfinished and inconclusive repetitions are never dropped. */
public record ThroughputResult(ThroughputSearch search, List<Repetition> repetitions) {
    public ThroughputResult {
        repetitions = List.copyOf(repetitions);
    }

    public record Repetition(int repetition, String discoveryDirectory, ThroughputStage.Result discovery,
                             String validationDirectory, ThroughputStage.Result validation) {
        public String outcome() { return validation == null ? discovery.outcome() : validation.outcome(); }
    }

    public Aggregate aggregate() {
        if (repetitions.size() != search.repetitions() || repetitions.stream().anyMatch(r -> r.validation() == null
                || !"BRACKETED".equals(r.validation().outcome()))) return null;
        var rates = repetitions.stream().map(r -> r.validation().highestPassingRate()).sorted().toList();
        int n = rates.size();
        return new Aggregate((rates.get((n - 1) / 2).doubleValue() + rates.get(n / 2)) / 2,
                rates.getFirst(), rates.getLast());
    }

    public record Aggregate(double medianPassingRate, int minimumPassingRate, int maximumPassingRate) { }
}
