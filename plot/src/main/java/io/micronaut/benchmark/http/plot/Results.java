package io.micronaut.benchmark.http.plot;

import io.hyperfoil.http.statistics.HttpStats;
import io.micronaut.benchmark.api.BenchmarkResult;
import io.micronaut.benchmark.api.BenchmarkStats;
import io.micronaut.benchmark.api.ThroughputResult;
import io.micronaut.benchmark.api.ThroughputSearch;
import io.micronaut.benchmark.api.ThroughputStage;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
        directory = directory.toAbsolutePath().normalize();
        Path metadataFile = directory.resolve("metadata.json");
        JsonNode savedMetadata = JSON.readTree(metadataFile.toFile());
        BenchmarkResult metadata = JSON.treeToValue(savedMetadata, BenchmarkResult.class);
        JsonNode run = JSON.readTree(directory.resolve("run.json").toFile());
        if (Files.exists(directory.resolve("search.json"))) {
            var search = JSON.readValue(directory.resolve("search.json").toFile(), ThroughputSearch.class);
            var result = Files.exists(directory.resolve("throughput.json"))
                    ? JSON.readValue(directory.resolve("throughput.json").toFile(), ThroughputResult.class)
                    : new ThroughputResult(search, List.of());
            var stages = new ArrayList<Summary>();
            Path repetitions = directory.resolve("repetitions");
            if (Files.isDirectory(repetitions)) {
                try (var paths = Files.walk(repetitions, 3)) {
                    for (Path stage : paths.filter(p -> p.getFileName().toString().equals("stage-plan.json")).sorted().toList()) {
                        stages.add(summary(stage.getParent()));
                    }
                }
            }
            var reassessed = new ArrayList<ThroughputResult.Repetition>();
            for (var repetition : result.repetitions()) {
                reassessed.add(new ThroughputResult.Repetition(repetition.repetition(), repetition.discoveryDirectory(),
                        stageResult(directory, stages, repetition.discoveryDirectory(), repetition.discovery()),
                        repetition.validationDirectory(), repetition.validationDirectory() == null ? null
                        : stageResult(directory, stages, repetition.validationDirectory(), repetition.validation())));
            }
            result = new ThroughputResult(result.search(), reassessed);
            return new Summary(directory.toAbsolutePath().toString(), run.path("state").asString(), metadata,
                    savedMetadata.path("profileCoverage").asString("unknown"), List.of(), List.of(), List.of(),
                    result, "SUCCEEDED".equals(run.path("state").asString()) ? result.aggregate() : null, stages, null);
        }
        Path raw = directory.resolve("output.json");
        if (!Files.exists(raw)) {
            raw = directory.resolve("output-failed.json");
        }
        BenchmarkStats stats = Files.exists(raw) ? JSON.readValue(raw.toFile(), BenchmarkStats.class)
                : new BenchmarkStats(new BenchmarkStats.Info(List.of()), List.of(), List.of());
        ThroughputStage.Result eligibility = null;
        if (Files.exists(directory.resolve("stage-plan.json"))) {
            var completion = Files.exists(directory.resolve("stage-completion.json"))
                    ? JSON.readValue(directory.resolve("stage-completion.json").toFile(), ThroughputStage.Completion.class)
                    : new ThroughputStage.Completion(false, false, List.of());
            var plan = JSON.readValue(directory.resolve("stage-plan.json").toFile(), ThroughputStage.class);
            eligibility = !"SUCCEEDED".equals(run.path("state").asString()) && Files.exists(directory.resolve("stage-result.json"))
                    ? JSON.readValue(directory.resolve("stage-result.json").toFile(), ThroughputStage.Result.class)
                    : plan.evaluate(stats, completion);
        }
        var phases = new ArrayList<Phase>();
        for (var phase : stats.stats() == null ? List.<BenchmarkStats.Stats>of() : stats.stats()) {
            if (phase.name() == null || !(phase.name().startsWith("main/") || phase.name().startsWith("ramp/"))
                    || phase.total() == null || phase.total().summary() == null) {
                continue;
            }
            var s = phase.total().summary();
            ThroughputStage.Observation observation = eligibility == null ? null : eligibility.phases().stream()
                    .filter(p -> p.name().equals(phase.name())).findFirst().orElse(null);
            double seconds = (s.endTime - s.startTime) / 1000.0;
            phases.add(new Phase(phase.name(), seconds, seconds > 0 ? s.requestCount / seconds : 0, seconds > 0 ? s.responseCount / seconds : 0,
                    s.meanResponseTime / 1e6, percentile(phase, 50), percentile(phase, 95), percentile(phase, 99),
                    s.invalid, s.connectionErrors, s.requestTimeouts, s.internalErrors,
                    observation == null ? null : observation.rate(), observation == null ? eligibility == null ? null : "EXCLUDED" : observation.status(),
                    observation == null ? eligibility == null ? null : "Unplanned phase" : observation.reason()));
        }
        return new Summary(directory.toAbsolutePath().toString(), run.get("state").stringValue(),
                metadata, savedMetadata.path("profileCoverage").asString("unknown"), phases, stats.failures(), stats.info() == null ? List.of() : stats.info().errors(),
                null, null, List.of(), eligibility);
    }

    private static ThroughputStage.Result stageResult(Path root, List<Summary> stages, String relative,
                                                     ThroughputStage.Result saved) {
        String directory = root.resolve(relative).toAbsolutePath().normalize().toString();
        return stages.stream().filter(s -> s.directory().equals(directory)).map(Summary::eligibility)
                .filter(Objects::nonNull).findFirst().orElse(saved);
    }

    private static Double percentile(BenchmarkStats.Stats phase, double percentile) {
        Map<Double, Long> values = phase.total().summary().percentileResponseTime;
        Long value = values == null ? null : values.get(percentile);
        if (value != null) return value / 1e6;
        // Hyperfoil's default summary omits p95, but its percentile histogram includes it.
        if (phase.histogram() != null && phase.histogram().percentiles() != null) {
            for (var point : phase.histogram().percentiles()) {
                if (point.percentile() == percentile / 100.0) return point.to() / 1e6;
            }
        }
        return null;
    }

    public static Comparison compare(Path baseline, Path candidate) throws IOException {
        Summary a = summary(baseline), b = summary(candidate);
        boolean adaptive = a.throughput() != null || b.throughput() != null || a.eligibility() != null || b.eligibility() != null;
        List<String> differences = new ArrayList<>();
        if (!workload(a, adaptive).equals(workload(b, adaptive))) {
            differences.add("Workload metadata differs");
        }
        if (!a.metadata().sutSpecs().equals(b.metadata().sutSpecs())) {
            differences.add("Machine specifications differ");
        }
        if (!Objects.equals(a.metadata().profiling(), b.metadata().profiling()) || !a.profileCoverage().equals(b.profileCoverage())) {
            differences.add("Profiling configuration differs");
        }
        Path aw = baseline.resolve("hyperfoil.yaml"), bw = candidate.resolve("hyperfoil.yaml");
        if (!adaptive && Files.exists(aw) && Files.exists(bw) && !Files.readString(aw).equals(Files.readString(bw))) {
            differences.add("Rendered workload/durations differ");
        }
        if (adaptive && !Objects.equals(searchSettings(baseline, a), searchSettings(candidate, b))) {
            differences.add("Throughput search settings differ");
        }
        if (adaptive && !Objects.equals(warmupSettings(baseline), warmupSettings(candidate))) {
            differences.add("Warmup configuration differs");
        }
        if (adaptive && (a.throughput() == null) != (b.throughput() == null)) differences.add("Benchmark modes differ");
        if (a.eligibility() != null || b.eligibility() != null) {
            if (a.eligibility() == null || b.eligibility() == null || !a.eligibility().stage().equals(b.eligibility().stage())) {
                differences.add("Measurement stages differ");
            }
            if (!Objects.equals(stageSettings(baseline), stageSettings(candidate))) differences.add("Stage durations differ");
        }
        if (!Objects.equals(environmentSettings(baseline), environmentSettings(candidate))) {
            differences.add("Environment configuration differs");
        }
        if (!generatorSettings(a).equals(generatorSettings(b))) differences.add("Load generator configuration differs");
        var deltas = new ArrayList<Map<String, Object>>();
        for (Phase pa : a.phases())
            for (Phase pb : b.phases())
                if (adaptive ? "PASS".equals(pa.status()) && "PASS".equals(pb.status())
                        && Objects.equals(pa.targetRate(), pb.targetRate()) && differences.isEmpty()
                        : pa.name().equals(pb.name())) {
                    var delta = new LinkedHashMap<String, Object>();
                    delta.put("phase", pa.name());
                    delta.put("responseRatePercent", percent(pa.responsesPerSecond(), pb.responsesPerSecond()));
                    delta.put("meanLatencyPercent", percent(pa.meanLatencyMs(), pb.meanLatencyMs()));
                    delta.put("p50LatencyPercent", percent(pa.p50LatencyMs(), pb.p50LatencyMs()));
                    delta.put("p95LatencyPercent", percent(pa.p95LatencyMs(), pb.p95LatencyMs()));
                    delta.put("p99LatencyPercent", percent(pa.p99LatencyMs(), pb.p99LatencyMs()));
                    deltas.add(delta);
                }
        Double throughputPercent = differences.isEmpty() && a.aggregate() != null && b.aggregate() != null
                ? percent(a.aggregate().medianPassingRate(), b.aggregate().medianPassingRate()) : null;
        return new Comparison(a, b, differences, deltas, throughputPercent);
    }

    private static JsonNode workload(Summary summary, boolean ignoreRates) {
        ObjectNode load = JSON.valueToTree(summary.metadata().load());
        if (ignoreRates) ((ObjectNode) load.path("protocol")).remove("ops");
        return load;
    }

    private static ThroughputSearch searchSettings(Path directory, Summary summary) throws IOException {
        if (summary.throughput() != null) return summary.throughput().search();
        Path search = directory.resolve(".nix/experiment/search.json");
        return Files.exists(search) ? JSON.readValue(search.toFile(), ThroughputSearch.class) : null;
    }

    private static Object stageSettings(Path directory) throws IOException {
        Path file = directory.resolve("stage-plan.json");
        if (!Files.exists(file)) return null;
        var plan = JSON.readValue(file.toFile(), ThroughputStage.class);
        return List.of(plan.warmupMillis(), plan.rampMillis(),
                plan.phases().stream().map(ThroughputStage.Phase::durationMillis).distinct().sorted().toList());
    }

    private static JsonNode warmupSettings(Path directory) throws IOException {
        Path file = directory.resolve("hyperfoil.yaml");
        if (!Files.exists(file)) return null;
        Object workload = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
        for (JsonNode phase : JSON.valueToTree(workload).path("phases")) {
            if (phase.has("warmup")) return phase.get("warmup");
        }
        return null;
    }

    private static JsonNode environmentSettings(Path directory) throws IOException {
        Path file = directory.resolve("environment.json");
        if (!Files.exists(file)) return null;
        ObjectNode settings = (ObjectNode) JSON.readTree(file.toFile());
        settings.remove("id"); // A new machine allocation with the same configuration is comparable.
        return settings;
    }

    private static java.util.Set<JsonNode> generatorSettings(Summary summary) throws IOException {
        var settings = new java.util.HashSet<JsonNode>();
        for (Summary stage : summary.throughput() == null ? List.of(summary) : summary.stages()) {
            Path file = Path.of(stage.directory()).resolve("meta.json");
            if (Files.exists(file)) settings.add(JSON.readTree(file.toFile()));
        }
        return settings;
    }

    private static Double percent(Double a, Double b) {
        return a == null || b == null || a == 0 ? null : (b / a - 1) * 100;
    }

    public record Phase(String name, double seconds, double requestsPerSecond, double responsesPerSecond,
                        double meanLatencyMs, Double p50LatencyMs, Double p95LatencyMs, Double p99LatencyMs,
                        int invalid, int connectionErrors, int requestTimeouts, int internalErrors,
                        Integer targetRate, String status, String exclusionReason) {
    }

    public record Summary(String directory, String state, BenchmarkResult metadata, String profileCoverage,
                          List<Phase> phases, List<BenchmarkStats.SlaFailure> slaFailures,
                          List<BenchmarkStats.Info.Error> errors, ThroughputResult throughput, ThroughputResult.Aggregate aggregate,
                          List<Summary> stages, ThroughputStage.Result eligibility) {
    }

    public record Comparison(Summary baseline, Summary candidate, List<String> differences,
                             List<Map<String, Object>> deltas, Double throughputPercent) {
    }
}
