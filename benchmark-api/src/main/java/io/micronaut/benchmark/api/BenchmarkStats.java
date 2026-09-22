package io.micronaut.benchmark.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.hyperfoil.api.statistics.StatisticsSummary;
import io.micronaut.core.annotation.Nullable;

import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BenchmarkStats(
        Info info,
        List<SlaFailure> failures,
        List<Stats> stats
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Info(
            List<Error> errors
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Error(
                String agent,
                String msg
        ) {

        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SlaFailure(
            String phase,
            String message
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stats(
            String phase,
            String name,
            Total total,
            Histogram histogram
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Total(StatisticsSummary summary) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Histogram(
            List<Percentile> percentiles
    ) {
    }

    public record Percentile(
            double from,
            double to,
            double percentile,
            long count,
            long totalCount
    ) {
    }

    @Nullable
    public Stats findPhase(String name) {
        for (Stats phase : stats) {
            if (phase.name.equals(name)) {
                return phase;
            }
        }
        return null;
    }

    @Nullable
    public Stats findPhaseContaining(Instant time) {
        for (Stats phase : stats) {
            if (phase.total.summary.startTime < time.toEpochMilli() && phase.total.summary.endTime > time.toEpochMilli()) {
                return phase;
            }
        }
        return null;
    }
}

