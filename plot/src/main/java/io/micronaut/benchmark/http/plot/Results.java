package io.micronaut.benchmark.http.plot;

import io.hyperfoil.http.statistics.HttpStats;
import io.micronaut.benchmark.api.BenchmarkResult;
import io.micronaut.benchmark.api.BenchmarkStats;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Offline entry points shared by the plot application and Micronaut CLI.
 */
public final class Results {
    private static final JsonMapper JSON = JsonMapper.builder().registerSubtypes(HttpStats.class).build();

    private Results() {
    }

    public static List<BenchmarkResult> index(Path root) throws IOException {
        List<Path> directories;
        if (Files.exists(root.resolve("metadata.json"))) {
            directories = List.of(root);
        } else {
            try (var paths = Files.list(root)) {
                directories = paths.filter(p -> Files.isDirectory(p) && Files.exists(p.resolve("metadata.json")))
                        .sorted().toList();
            }
        }
        var results = new ArrayList<BenchmarkResult>();
        for (Path directory : directories) {
            if (!Files.exists(directory.resolve("output.json"))) {
                continue;
            }
            Path record = directory.resolve("run.json");
            if (!Files.exists(record) || !"SUCCEEDED".equals(JSON.readTree(record.toFile()).path("state").asString())) {
                continue;
            }
            BenchmarkResult result = JSON.readValue(directory.resolve("metadata.json").toFile(), BenchmarkResult.class);
            String path = root.relativize(directory).toString();
            results.add(new BenchmarkResult(path.isEmpty() ? "." : path, result.type(), result.parameters(), result.profiling(), result.load(), result.sutSpecs()));
        }
        return results;
    }

    public static Summary summary(Path directory) throws IOException {
        Path metadataFile = directory.resolve("metadata.json");
        JsonNode savedMetadata = JSON.readTree(metadataFile.toFile());
        BenchmarkResult metadata = JSON.treeToValue(savedMetadata, BenchmarkResult.class);
        Path raw = directory.resolve("output.json");
        if (!Files.exists(raw)) {
            raw = directory.resolve("output-failed.json");
        }
        BenchmarkStats stats = JSON.readValue(raw.toFile(), BenchmarkStats.class);
        var phases = new ArrayList<Phase>();
        for (var phase : stats.stats()) {
            if (!phase.name().startsWith("main/")) {
                continue;
            }
            var s = phase.total().summary();
            double seconds = (s.endTime - s.startTime) / 1000.0;
            phases.add(new Phase(phase.name(), seconds, seconds > 0 ? s.requestCount / seconds : 0, seconds > 0 ? s.responseCount / seconds : 0,
                    s.meanResponseTime / 1e6, percentile(s.percentileResponseTime, 50), percentile(s.percentileResponseTime, 99),
                    s.invalid, s.connectionErrors, s.requestTimeouts, s.internalErrors));
        }
        JsonNode run = JSON.readTree(directory.resolve("run.json").toFile());
        return new Summary(directory.toAbsolutePath().toString(), run.get("state").stringValue(),
                metadata, savedMetadata.path("profileCoverage").asString("unknown"), phases, stats.failures(), stats.info().errors());
    }

    private static Double percentile(Map<Double, Long> values, double percentile) {
        Long value = values.get(percentile);
        return value == null ? null : value / 1e6;
    }

    public static Comparison compare(Path baseline, Path candidate) throws IOException {
        Summary a = summary(baseline), b = summary(candidate);
        List<String> differences = new ArrayList<>();
        if (!a.metadata().load().equals(b.metadata().load())) {
            differences.add("Workload metadata differs");
        }
        if (!a.metadata().sutSpecs().equals(b.metadata().sutSpecs())) {
            differences.add("Machine specifications differ");
        }
        if (!Objects.equals(a.metadata().profiling(), b.metadata().profiling())) {
            differences.add("Profiling configuration differs");
        }
        Path aw = baseline.resolve("hyperfoil.yaml"), bw = candidate.resolve("hyperfoil.yaml");
        if (Files.exists(aw) && Files.exists(bw) && !Files.readString(aw).equals(Files.readString(bw))) {
            differences.add("Rendered workload/durations differ");
        }
        Path ae = baseline.resolve("environment.json"), be = candidate.resolve("environment.json");
        if (Files.exists(ae) && Files.exists(be) && !JSON.readTree(ae.toFile()).equals(JSON.readTree(be.toFile()))) {
            differences.add("Environment identity or configuration differs");
        }
        var deltas = new ArrayList<Map<String, Object>>();
        for (Phase pa : a.phases())
            for (Phase pb : b.phases())
                if (pa.name().equals(pb.name())) {
                    var delta = new LinkedHashMap<String, Object>();
                    delta.put("phase", pa.name());
                    delta.put("responseRatePercent", percent(pa.responsesPerSecond(), pb.responsesPerSecond()));
                    delta.put("meanLatencyPercent", percent(pa.meanLatencyMs(), pb.meanLatencyMs()));
                    delta.put("p99LatencyPercent", percent(pa.p99LatencyMs(), pb.p99LatencyMs()));
                    deltas.add(delta);
                }
        return new Comparison(a, b, differences, deltas);
    }

    private static Double percent(Double a, Double b) {
        return a == null || b == null || a == 0 ? null : (b / a - 1) * 100;
    }

    public record Phase(String name, double seconds, double requestsPerSecond, double responsesPerSecond,
                        double meanLatencyMs, Double p50LatencyMs, Double p99LatencyMs,
                        int invalid, int connectionErrors, int requestTimeouts, int internalErrors) {
    }

    public record Summary(String directory, String state, BenchmarkResult metadata, String profileCoverage,
                          List<Phase> phases, List<BenchmarkStats.SlaFailure> slaFailures,
                          List<BenchmarkStats.Info.Error> errors) {
    }

    public record Comparison(Summary baseline, Summary candidate, List<String> differences,
                             List<Map<String, Object>> deltas) {
    }
}
