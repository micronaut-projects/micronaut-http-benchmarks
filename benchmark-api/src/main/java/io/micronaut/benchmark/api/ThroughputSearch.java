package io.micronaut.benchmark.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Immutable search settings retained in the experiment closure. */
public record ThroughputSearch(String preset, int startRate, int maxRate,
                               String warmupDuration, String discoveryDuration, String validationDuration,
                               double discoveryStep, double validationStep, int repetitions, double sessionLimitFactor) {
    public ThroughputSearch {
        if (!List.of("quick", "thorough").contains(preset)) throw new IllegalArgumentException("Unknown preset: " + preset);
        if (startRate < 1 || maxRate < startRate) throw new IllegalArgumentException("Require 0 < start rate <= maximum rate");
        if (repetitions < 1) throw new IllegalArgumentException("Repetitions must be positive");
        checkStep(discoveryStep);
        checkStep(validationStep);
        milliseconds(warmupDuration);
        milliseconds(discoveryDuration);
        milliseconds(validationDuration);
        if (!Double.isFinite(sessionLimitFactor) || sessionLimitFactor <= 0
                || Math.ceil(maxRate * sessionLimitFactor) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid session limit factor or excessive session capacity");
        }
    }

    private static void checkStep(double step) {
        if (!Double.isFinite(step) || step <= 0 || step > 100) {
            throw new IllegalArgumentException("Step percentage must be in (0, 100]");
        }
    }

    public static long milliseconds(String duration) {
        if (duration == null || !duration.matches("[1-9][0-9]*(s|m|h)")) {
            throw new IllegalArgumentException("Invalid duration: " + duration);
        }
        long multiplier = switch (duration.charAt(duration.length() - 1)) {
            case 's' -> 1000;
            case 'm' -> 60_000;
            default -> 3_600_000;
        };
        return Math.multiplyExact(Long.parseLong(duration.substring(0, duration.length() - 1)), multiplier);
    }

    public static List<Integer> rates(int start, int end, double step) {
        checkStep(step);
        if (start < 1 || end < start) throw new IllegalArgumentException("Invalid rate range");
        var rates = new ArrayList<Integer>();
        BigDecimal factor = BigDecimal.ONE.add(BigDecimal.valueOf(step).movePointLeft(2));
        int rate = start;
        while (true) {
            rates.add(rate);
            if (rate == end) return List.copyOf(rates);
            if (rates.size() >= 1000) throw new IllegalArgumentException("Search would exceed 1000 phases; increase step size");
            rate = BigDecimal.valueOf(rate).multiply(factor).setScale(0, RoundingMode.CEILING)
                    .min(BigDecimal.valueOf(end)).intValueExact();
        }
    }

    public ThroughputStage discovery() {
        return stage("discovery", rates(startRate, maxRate, discoveryStep), discoveryDuration);
    }

    public ThroughputStage validation(ThroughputStage.Result discovery) {
        if (!discovery.canValidate()) {
            throw new IllegalArgumentException("Discovery did not establish a usable range");
        }
        int passing = discovery.highestPassingRate();
        int start = percentage(passing, 90);
        Integer endpoint = discovery.firstFailingRate();
        // The fresh validation process may sustain more load than discovery did.
        int end = endpoint == null ? maxRate : (int) Math.min(maxRate, (endpoint * 125L + 99) / 100);
        var validationRates = new ArrayList<Integer>();
        // These coarse steps are measured phases, subject to the ordinary SLA cutoff.
        for (int percent : List.of(25, 50, 75)) {
            int rate = percentage(passing, percent);
            if (rate < start && (validationRates.isEmpty() || rate > validationRates.getLast())) {
                validationRates.add(rate);
            }
        }
        validationRates.addAll(rates(start, end, validationStep));
        return stage("validation", validationRates, validationDuration);
    }

    private static int percentage(int rate, int percent) {
        return (int) ((rate * (long) percent + 99) / 100);
    }

    private ThroughputStage stage(String stage, List<Integer> rates, String duration) {
        return new ThroughputStage(stage, milliseconds(warmupDuration),
                java.util.stream.IntStream.range(0, rates.size())
                        .mapToObj(i -> new ThroughputStage.Phase("main/" + i, rates.get(i), milliseconds(duration))).toList());
    }
}
