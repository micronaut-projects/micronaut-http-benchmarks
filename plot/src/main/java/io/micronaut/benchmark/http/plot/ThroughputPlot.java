package io.micronaut.benchmark.http.plot;

import io.micronaut.benchmark.api.InstanceType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Standalone report: unlike fixed-load charts, phase indexes across searches are not comparable. */
final class ThroughputPlot {
    static List<Path> directories(Path root) throws IOException {
        if (Files.exists(root.resolve("metadata.json"))) return List.of(root);
        try (var paths = Files.list(root)) {
            return paths.filter(p -> Files.exists(p.resolve("metadata.json"))).sorted().toList();
        }
    }

    static boolean applicable(Path root) throws IOException {
        return directories(root).stream().anyMatch(p -> Files.exists(p.resolve("search.json")) || Files.exists(p.resolve("stage-plan.json")));
    }

    static String render(Path root) throws IOException, InterruptedException {
        return render(root, new ArrayList<>());
    }

    static String render(Path root, List<ProfileConverter.ProfileArtifacts> profiles) throws IOException, InterruptedException {
        var summaries = new ArrayList<Results.Summary>();
        for (Path directory : directories(root)) summaries.add(Results.summary(directory));
        var html = new StringBuilder("""
                <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Throughput comparison</title>
                <style>body{font:15px system-ui,sans-serif;margin:40px auto;max-width:1200px;padding:0 24px;color:#182631;background:#f7f9fb}
                section{background:white;padding:24px;margin:24px 0;border:1px solid #dce3e9;border-radius:12px}
                table{border-collapse:collapse;width:100%;margin:16px 0}td,th{text-align:left;padding:8px;border-bottom:1px solid #e5eaf0}
                th{color:#526474}.excluded{color:#7a8590;background:#f4f5f7}.pass{color:#176544}details{margin:16px 0}
                pre{white-space:pre-wrap;overflow-wrap:anywhere}input{padding:10px;width:50%;border:1px solid #b4c1ce;border-radius:6px}
                small{color:#526474}</style>
                <h1>Throughput comparison</h1><p>Only the consecutive passing validation phases establish throughput.
                Bounds describe tested rates; step size is not statistical confidence. Discovery and excluded phases are diagnostics.</p>
                """);
        html.append(ChartEmitter.scripts()).append("<script>").append(Main.loadStatic("/throughput.js")).append("</script><style>")
                .append(Main.loadStatic("/throughput.css")).append("</style>");
        ThroughputCharts.emit(html, summaries);
        html.append("""
                <input aria-label="Filter result details" placeholder="Filter result details" oninput="document.querySelectorAll('section').forEach(s=>s.hidden=!s.textContent.toLowerCase().includes(this.value.toLowerCase()))">
                """);
        for (var s : summaries) {
            html.append("<section><h2>").append(escape(s.metadata().name())).append("</h2><small>")
                    .append(escape(s.directory())).append(" · ").append(escape(s.state())).append("</small>");
            environment(html, s);
            if (s != summaries.getFirst()) {
                var differences = Results.compare(Path.of(summaries.getFirst().directory()), Path.of(s.directory())).differences();
                if (!differences.isEmpty()) html.append("<p>Settings differ from the first result: ")
                        .append(escape(String.join("; ", differences))).append(".</p>");
            }
            if (s.throughput() != null) {
                html.append("<p>").append(escape(s.throughput().search().preset())).append(" · ")
                        .append(s.throughput().repetitions().size()).append(" / ").append(s.throughput().search().repetitions()).append(" repetitions</p>");
                if (s.aggregate() != null) html.append("<p>Median passing RPS: <strong>").append(s.aggregate().medianPassingRate())
                        .append("</strong> · range ").append(s.aggregate().minimumPassingRate()).append("–").append(s.aggregate().maximumPassingRate()).append("</p>");
                for (var r : s.throughput().repetitions()) {
                    var result = r.validation();
                    html.append("<h3>Repetition ").append(r.repetition()).append("</h3>");
                    if (result == null) {
                        html.append("<p>No validation result. Discovery: ").append(escape(r.discovery().outcome())).append("</p>");
                    } else {
                        html.append("<p>").append(escape(result.outcome())).append(" · passing RPS: ")
                                .append(result.highestPassingRate() == null ? "—" : result.highestPassingRate())
                                .append(" · first failing RPS: ").append(result.firstFailingRate() == null ? "—" : result.firstFailingRate()).append("</p>");
                        if (result.reason() != null) html.append("<p>").append(escape(result.reason())).append("</p>");
                    }
                }
                for (var stage : s.stages()) {
                    stage(html, stage);
                    profile(root, html, stage, profiles);
                }
            } else {
                stage(html, s);
                profile(root, html, s, profiles);
            }
            html.append("<details><summary>Metadata and complete results</summary><pre>")
                    .append(escape(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(s)))
                    .append("</pre></details></section>");
        }
        return html.append("</html>").toString();
    }

    private static void environment(StringBuilder html, Results.Summary summary) throws IOException {
        var metadata = summary.metadata();
        var mapper = JsonMapper.builder().build();
        html.append("<h3>Benchmark environment</h3><dl class='benchmark-environment'><dt>SUT</dt><dd>")
                .append(LoadGroup.formatSut(metadata.sutSpecs())).append("</dd>");
        environmentValue(html, "Workload", metadata.load().name());
        environmentValue(html, "Protocol", metadata.load().protocol().protocol().name());
        environmentValue(html, "Connections / HTTP2 streams", metadata.load().protocol().sharedConnections()
                + " / " + metadata.load().protocol().maxHttp2Streams());
        environmentValue(html, "Percentile SLAs", metadata.load().protocol().sla().toString());
        JsonNode parameters = mapper.valueToTree(metadata.parameters());
        if (parameters.isObject()) parameters.properties().forEach(entry ->
                environmentValue(html, entry.getKey(), entry.getValue().isValueNode() ? entry.getValue().asString() : entry.getValue().toString()));
        if (metadata.profiling() != null) environmentValue(html, "Profiling", metadata.profiling().tool() + " · " + summary.profileCoverage());
        if (summary.throughput() != null) {
            var search = summary.throughput().search();
            environmentValue(html, "Measurement", search.preset() + " · warmup " + search.warmupDuration()
                    + " · discovery " + search.discoveryDuration() + " / +" + search.discoveryStep()
                    + "% · discovery ramp " + search.discoveryRampDuration()
                    + " · validation " + search.validationDuration() + " / +" + search.validationStep() + "%");
        }
        Path savedEnvironment = Path.of(summary.directory()).resolve("environment.json");
        if (!Files.isRegularFile(savedEnvironment)) savedEnvironment = summary.stages().stream()
                .map(stage -> Path.of(stage.directory()).resolve("environment.json")).filter(Files::isRegularFile).findFirst().orElse(null);
        if (savedEnvironment != null && Files.isRegularFile(savedEnvironment)) {
            var environment = mapper.readTree(savedEnvironment.toFile());
            var location = environment.path("location");
            if (location.has("region")) environmentValue(html, "Location", location.path("region").asString()
                    + " · " + location.path("availabilityDomain").asString(""));
            var infrastructure = environment.path("infrastructure");
            for (String role : List.of("hyperfoil-agent", "hyperfoil-controller")) {
                var instance = infrastructure.path("instanceTypes").path(role);
                if (instance.isObject()) html.append("<dt>").append(role).append("</dt><dd>")
                        .append(LoadGroup.formatSut(mapper.treeToValue(instance, InstanceType.class))).append("</dd>");
            }
            if (infrastructure.has("kernel")) environmentValue(html, "Kernel", Path.of(infrastructure.path("kernel").asString()).getFileName().toString());
        }
        html.append("</dl>");
    }

    private static void environmentValue(StringBuilder html, String label, String value) {
        html.append("<dt>").append(escape(label)).append("</dt><dd>").append(escape(value)).append("</dd>");
    }

    private static void profile(Path root, StringBuilder html, Results.Summary stage,
                                List<ProfileConverter.ProfileArtifacts> profiles) throws IOException, InterruptedException {
        var metadata = stage.metadata().profiling();
        Path directory = Path.of(stage.directory());
        if (metadata == null || !Files.isRegularFile(directory.resolve(metadata.artifact()))) return;
        ProfileConverter.ProfileArtifacts profile;
        try {
            profile = ProfileConverter.convert(directory, metadata);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Failed to convert profile for " + directory + ": " + e.getMessage());
            profile = ProfileConverter.ProfileArtifacts.failed(metadata, directory.resolve(metadata.artifact()));
        }
        profiles.add(profile);
        String relative = root.toAbsolutePath().normalize().relativize(directory).toString();
        html.append("<p>Process-lifetime profile for ").append(escape(relative.isEmpty() ? directory.getFileName().toString() : relative))
                .append(" (includes warmup and excluded phases): ");
        profileLink(root, html, profile.raw(), profile.raw() != null && profile.raw().toString().endsWith(".jfr") ? "download JFR" : "recording");
        profileLink(root, html, profile.flamegraph(), "flamegraph");
        profileLink(root, html, profile.reverseFlamegraph(), "reverse flamegraph");
        profileLink(root, html, profile.heatmap(), "heatmap");
        html.append("</p>");
    }

    private static void profileLink(Path root, StringBuilder html, Path file, String label) {
        if (file != null && Files.isRegularFile(file)) html.append("<a href=\"")
                .append(escape(root.toAbsolutePath().normalize().toUri().relativize(file.toUri()).toASCIIString()))
                .append(file.toString().endsWith(".jfr") ? "\" download>" : "\">").append(label).append("</a> ");
    }

    private static void stage(StringBuilder html, Results.Summary stage) {
        html.append("<details><summary>").append(escape(stage.directory())).append("</summary>")
                .append("<table><tr><th>Phase</th><th>Target RPS</th><th>Requests/s</th><th>Responses/s</th><th>p50 ms</th><th>p95 ms</th><th>p99 ms</th><th>Status / reason</th></tr>");
        for (var phase : stage.phases()) {
            boolean eligible = "PASS".equals(phase.status()) && stage.eligibility() != null && "validation".equals(stage.eligibility().stage());
            html.append("<tr class='").append(eligible ? "pass" : "excluded").append("'><td>").append(escape(phase.name()))
                    .append("</td><td>").append(phase.targetRate()).append("</td><td>").append(String.format(Locale.ROOT, "%.1f", phase.requestsPerSecond()))
                    .append("</td><td>").append(String.format(Locale.ROOT, "%.1f", phase.responsesPerSecond()))
                    .append("</td><td>").append(phase.p50LatencyMs()).append("</td><td>").append(phase.p95LatencyMs())
                    .append("</td><td>").append(phase.p99LatencyMs()).append("</td><td>").append(escape(phase.status()))
                    .append(phase.exclusionReason() == null ? "" : ": " + escape(phase.exclusionReason())).append("</td></tr>");
        }
        html.append("</table></details>");
    }

    static String escape(String s) {
        return s == null ? "—" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
