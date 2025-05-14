package io.micronaut.benchmark.http.plot;

import io.micronaut.benchmark.loadgen.oci.HyperfoilRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class JfrSummary {
    final Map<String, PhaseSummary> phases = new HashMap<>();

    PhaseSummary phase(HyperfoilRunner.StatsAll.Stats phase) {
        return phases.computeIfAbsent(phase.name(), k -> new PhaseSummary(Duration.ofNanos(phase.total().summary().endTime - phase.total().summary().startTime)));
    }

    public static final class PhaseSummary {
        final Duration duration;
        long executionSamples;
        final List<Float> jvmUser = new ArrayList<>();
        final List<Float> jvmSystem = new ArrayList<>();
        final List<Float> machineTotal = new ArrayList<>();

        PhaseSummary(Duration duration) {
            this.duration = duration;
        }

        double get(CpuUsageMetric metric) {
            return switch (metric) {
                case EXECUTION_SAMPLES -> (double) executionSamples / duration.toNanos();
                case JVM_USER -> jvmUser.stream().mapToDouble(Float::doubleValue).average().orElseThrow();
                case JVM_SYSTEM -> jvmSystem.stream().mapToDouble(Float::doubleValue).average().orElseThrow();
                case JVM -> get(CpuUsageMetric.JVM_USER) + get(CpuUsageMetric.JVM_SYSTEM);
                case MACHINE_TOTAL -> machineTotal.stream().mapToDouble(Float::doubleValue).average().orElseThrow();
            };
        }
    }
}
