package io.micronaut.benchmark.http.plot;

import io.hyperfoil.impl.Util;
import io.micronaut.benchmark.api.ThroughputStage;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Plot eligible observations and explicitly marked combined SLA/generator failures; never plot later phases. */
final class ThroughputCharts {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Curve(String label, String stage, List<Results.Phase> phases) { }
    record Bound(String label, String outcome, Integer passing, Integer failing) { }
    record Run(String label, String color, String profiler, List<Curve> curves, List<Bound> bounds) { }
    record Data(List<Run> runs, Map<Double, Long> sla) { }

    static Data data(List<Results.Summary> summaries) {
        return data(summaries, summaries);
    }

    private static String identity(Results.Summary summary) {
        return JSON.writeValueAsString(java.util.Arrays.asList(summary.metadata().type(), summary.metadata().parameters()));
    }

    private static Data data(List<Results.Summary> summaries, List<Results.Summary> all) {
        var identities = all.stream().map(ThroughputCharts::identity).distinct().sorted().toList();
        var runs = new ArrayList<Run>();
        for (var summary : summaries) {
            var curves = new ArrayList<Curve>();
            var bounds = new ArrayList<Bound>();
            var stages = summary.eligibility() == null ? summary.stages() : List.of(summary);
            for (var stage : stages) {
                if (stage.eligibility() == null) continue;
                String label = Path.of(summary.directory()).relativize(Path.of(stage.directory())).toString();
                if (label.isEmpty()) label = stage.eligibility().stage();
                var points = stage.phases().stream()
                        .filter(p -> p.targetRate() != null && List.of("PASS", "FAIL").contains(p.status()))
                        .sorted(Comparator.comparingInt(Results.Phase::targetRate)).toList();
                curves.add(new Curve(label, stage.eligibility().stage(), points));
            }
            if (summary.throughput() != null) {
                for (var repetition : summary.throughput().repetitions()) {
                    bounds.add(bound("Repetition " + repetition.repetition(), summary.state(), repetition.validation()));
                }
            } else if (summary.eligibility() != null && "validation".equals(summary.eligibility().stage())) {
                bounds.add(bound("Validation", summary.state(), summary.eligibility()));
            }
            // Identical SUT configurations keep the same color across repetitions and saved runs.
            String name = summary.metadata().name();
            if (summaries.stream().filter(s -> s.metadata().name().equals(summary.metadata().name())).count() > 1)
                name += " · " + Path.of(summary.directory()).getFileName();
            String profiler = summary.metadata().profiling() == null ? "off" : summary.metadata().profiling().tool();
            runs.add(new Run(name, PlotColors.color(identities.indexOf(identity(summary)), 1, .8), profiler, curves, bounds));
        }
        var sla = new LinkedHashMap<Double, Long>();
        if (!summaries.isEmpty()) summaries.getFirst().metadata().load().protocol().sla()
                .forEach((percentile, limit) -> sla.put(percentile, Util.parseToNanos(limit)));
        return new Data(runs, sla);
    }

    private static Bound bound(String label, String state, ThroughputStage.Result result) {
        String outcome = !"SUCCEEDED".equals(state) ? state : result == null ? "NO_VALIDATION" : result.outcome();
        boolean usable = result != null && "SUCCEEDED".equals(state)
                && List.of("BRACKETED", "LOWER_BOUND").contains(result.outcome());
        return new Bound(label, outcome, usable ? result.highestPassingRate() : null, usable ? result.firstFailingRate() : null);
    }

    static List<List<Results.Summary>> groups(List<Results.Summary> summaries) throws IOException {
        // Keep workload/SLA/machine differences separate. Profiler differences are annotations
        // in head-to-head charts; the strict comparison API still reports them.
        var groups = new ArrayList<List<Results.Summary>>();
        for (var summary : summaries) {
            List<Results.Summary> group = null;
            for (var candidate : groups) {
                if (Results.compare(Path.of(candidate.getFirst().directory()), Path.of(summary.directory())).differences()
                        .stream().allMatch("Profiling configuration differs"::equals)) {
                    group = candidate;
                    break;
                }
            }
            if (group == null) { group = new ArrayList<>(); groups.add(group); }
            group.add(summary);
        }
        return groups;
    }

    static void emit(StringBuilder html, List<Results.Summary> summaries) throws IOException {
        var groups = groups(summaries);
        html.append("<div class='chart-controls'><label><input type='checkbox' checked onchange=\"showThroughputStage('discovery',this.checked)\"> Discovery (dashed)</label> ")
                .append("<label><input type='checkbox' checked onchange=\"showThroughputStage('validation',this.checked)\"> Validation (solid)</label>")
                .append("<span>× first SLA/session-limit failure · shaded area violates the configured percentile SLA</span></div>")
                .append("<p class='chart-note'>Latency axes are logarithmic and capped at 2× each SLA. Higher values remain available in the phase details.</p>");
        int index = 0;
        for (var group : groups) {
            Data data = data(group, summaries);
            String id = "throughputData" + UUID.randomUUID().toString().replace("-", "");
            html.append("<div class='throughput-graphs'>");
            if (groups.size() > 1) html.append("<h2 class='chart-group-heading'>Measurement settings group ").append(++index).append("</h2>");
            html.append("<script>const ").append(id).append("=")
                    .append(JSON.writeValueAsString(data).replace("<", "\\u003c")).append(";</script>");
            int bars = data.runs().stream().mapToInt(r -> r.bounds().size()).sum();
            html.append("<div class='throughput-capacity'><h2>Validated throughput</h2><p>Bars show passing offered RPS; ≥ denotes a lower bound. Unavailable outcomes have no bar.</p>")
                    .append("<div style='height:").append(Math.max(170, 80 + bars * 55)).append("px'>");
            new ChartEmitter("throughputChart('capacity'," + id + ")").collection("throughputCharts").wrapperClass("throughput-canvas").emit(html);
            html.append("</div></div>");
            html.append("<div class='throughput-legend' role='group' aria-label='Latency curves'>");
            for (var run : data.runs()) {
                html.append("<label><input type='checkbox' checked data-throughput-run='").append(ThroughputPlot.escape(run.label()))
                        .append("' onchange='showThroughputRun(this.dataset.throughputRun,this.checked)'><span class='run-swatch' style='background:")
                        .append(run.color()).append("'></span><span>").append(ThroughputPlot.escape(run.label()))
                        .append("<small>Profiler: ").append(ThroughputPlot.escape(run.profiler())).append("</small></span></label>");
            }
            if (data.runs().stream().map(Run::profiler).distinct().count() > 1) {
                html.append("<p class='chart-note'>These servers use different profiling tools; profiling overhead may differ.</p>");
            }
            html.append("</div>");
            for (String percentile : List.of("p50", "p95", "p99")) {
                html.append("<div><h2>").append(percentile.toUpperCase(java.util.Locale.ROOT)).append(" latency</h2><div class='throughput-latency'>");
                new ChartEmitter("throughputChart('" + percentile + "'," + id + ")")
                        .collection("throughputCharts").wrapperClass("throughput-canvas").emit(html);
                html.append("</div></div>");
            }
            html.append("</div>");
        }
    }
}
