package io.micronaut.benchmark.http.plot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.hyperfoil.http.statistics.HttpStats;
import io.micronaut.benchmark.loadgen.oci.HyperfoilRunner;
import io.micronaut.benchmark.loadgen.oci.SuiteRunner;
import one.jfr.JfrReader;
import one.jfr.event.CPULoad;
import one.jfr.event.Event;
import one.jfr.event.ExecutionSample;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class Main {
    private static final Path OUTPUT = Path.of("output");

    static final List<Discriminator> DISCRIMINATORS = List.of(
            new Discriminator("type", SuiteRunner.BenchmarkParameters::type),
            new Discriminator("Hotspot options", p -> ((Map<?, ?>) p.parameters()).get("hotspotOptions").toString()),
            new Discriminator("Request", p -> p.load().protocol().protocol().name() + " " + p.load().definition().getMethod() + " " + p.load().definition().getUri())
                    .selectWithDropdown(true),
            new Discriminator("Micronaut version", p -> compileConfiguration(p, "micronaut")),
            new Discriminator("JSON implementation", p -> compileConfiguration(p, "json")),
            new Discriminator("Netty transport", p -> compileConfiguration(p, "transport")),
            new Discriminator("tcnative support", p -> compileConfiguration(p, "tcnative")),
            new Discriminator("loom support", p -> compileConfiguration(p, "loom"))
                    .order(List.of("off", "carried", "on")),
            new Discriminator("http client thread affinity mode", p -> compileConfiguration(p, "affinity"))
                    .order(List.of("enforced", "preferred", "off"))
    );

    private final ObjectMapper mapper = JsonMapper.builder()
            .registerSubtypes(HttpStats.class)
            .build();
    private final Map<String, HyperfoilRunner.StatsAll> benchmarkOutput = new HashMap<>();
    private final double minTime;
    private final double maxTime = Duration.ofMillis(200).toNanos();
    private final List<SuiteRunner.BenchmarkParameters> index;
    private final boolean asyncProfiler;
    private final Map<SuiteRunner.BenchmarkParameters, JfrSummary> jfrSummaries;

    private Main() throws IOException {
        index = mapper.readValue(OUTPUT.resolve("index.json").toFile(), new TypeReference<>() {
        });
        index.sort(Comparator.comparing(SuiteRunner.BenchmarkParameters::name));
        index.removeIf(p -> {
            HyperfoilRunner.StatsAll statsAll = getBenchmark(p.name());
            if (statsAll.findPhase("main/0") == null) {
                System.out.println("Benchmark run " + p.name() + " failed");
                return true;
            }
            return false;
        });

        minTime = index.stream()
                .map(p -> getBenchmark(p.name()))
                .flatMap(s -> s.stats().stream())
                .flatMap(s -> s.histogram().percentiles().stream())
                .mapToDouble(HyperfoilRunner.StatsAll.Percentile::to)
                .min().orElseThrow();

        asyncProfiler = index.stream().anyMatch(p -> p.name().contains("-async-profiler"));
        if (asyncProfiler && !index.stream().allMatch(p -> p.name().contains("-async-profiler"))) {
            throw new IllegalStateException("Can't mix async-profiler with normal results");
        }

        if (asyncProfiler) {
            jfrSummaries = new HashMap<>();
            for (SuiteRunner.BenchmarkParameters parameters : index) {
                JfrSummary summary = new JfrSummary();
                jfrSummaries.put(parameters, summary);
                try (JfrReader jfr = new JfrReader(OUTPUT.resolve(parameters.name()).resolve("profile.jfr").toString())) {
                    while (true) {
                        Event event = jfr.readEvent();
                        if (event == null) {
                            break;
                        }
                        // from JfrToHeatmap
                        long msFromStart = (event.time - jfr.chunkStartTicks) * 1_000 / jfr.ticksPerSec;
                        Instant time = Instant.ofEpochMilli(jfr.chunkStartNanos / 1_000_000 + msFromStart);
                        HyperfoilRunner.StatsAll.Stats phase = getBenchmark(parameters.name()).findPhaseContaining(time);

                        if (phase != null) {
                            if (event instanceof ExecutionSample es) {
                                summary.phase(phase).executionSamples += es.samples;
                            } else if (event instanceof CPULoad cl) {
                                summary.phase(phase).jvmUser.add(cl.jvmUser);
                                summary.phase(phase).jvmSystem.add(cl.jvmSystem);
                                summary.phase(phase).machineTotal.add(cl.machineTotal);
                            }
                        }
                    }
                }
            }
        } else {
            jfrSummaries = null;
        }
    }

    private HyperfoilRunner.StatsAll getBenchmark(String benchmarkName) {
        return benchmarkOutput.computeIfAbsent(benchmarkName, n -> {
            Path path = OUTPUT.resolve(n).resolve("output.json");
            try {
                return mapper.readValue(path.toFile(), HyperfoilRunner.StatsAll.class);
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse " + path, e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static String compileConfiguration(SuiteRunner.BenchmarkParameters parameters, String name) {
        Map<String, Object> compileConfiguration = ((Map<String, Object>) ((Map<String, Object>) parameters.parameters()).get("compileConfiguration"));
        Object v = compileConfiguration.get(name);
        return v == null ? "" : v.toString();
    }

    private static String loadStatic(String name) {
        try (InputStream is = Main.class.getResourceAsStream(name)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String plot() {
        Map<CpuUsageMetric, Double> maxCpu;
        DropdownSelector cpuMetricSelector;
        Map<CpuUsageMetric, DropdownSelector.OptionAttribute> metricAttributes;
        if (asyncProfiler) {
            cpuMetricSelector = new DropdownSelector();
            metricAttributes = new EnumMap<>(CpuUsageMetric.class);
            for (CpuUsageMetric metric : CpuUsageMetric.values()) {
                metricAttributes.put(metric, cpuMetricSelector.addOption(metric.name()));
            }
            maxCpu = new HashMap<>();
            for (JfrSummary summary : jfrSummaries.values()) {
                for (JfrSummary.PhaseSummary phase : summary.phases.values()) {
                    for (CpuUsageMetric metric : CpuUsageMetric.values()) {
                        maxCpu.compute(metric, (_, v) -> Math.max(v == null ? 0 : v, phase.get(metric)));
                    }
                }
            }
        } else {
            cpuMetricSelector = null;
            metricAttributes = null;
            maxCpu = null;
        }

        LoadGroup loadGroup = new LoadGroup()
                .time(minTime, maxTime)
                .maxCpu(maxCpu)
                .metricAttributes(metricAttributes);

        for (SuiteRunner.BenchmarkParameters parameters : index) {
            loadGroup.add(
                    parameters,
                    getBenchmark(parameters.name()),
                    jfrSummaries == null ? null : jfrSummaries.get(parameters)
            );
        }

        loadGroup.complete();

        StringBuilder html = new StringBuilder("""
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="UTF-8">
                 <meta name="viewport" content="width=device-width, initial-scale=1">
                 <title>micronaut-http-benchmarks result</title>
                 <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.9/dist/chart.umd.min.js"></script>
                 <script>
                """ + loadStatic("/static.js") + """
                </script>
                <style>
                """ + loadStatic("/static.css") + """
                 </style>
                """);

        if (cpuMetricSelector != null) {
            cpuMetricSelector.emitHead(html);
        }
        loadGroup.emitHead(html);

        html.append("</head><body><div id='legend'>");
        html.append("""
                <p>
                Request latency at different request rates. Each graph represents a fixed request rate.
                Each request latency is recorded and shown in the graph. The horizontal axis is the latency percentile, the vertical axis the latency at that percentile.
                For fairness, each framework is tested on the same infrastructure (server VM + client VMs) in random order. To reduce noise, the suite is repeated on independent infrastructures. The results of each infrastructure benchmark are combined to produce the plotted line.
                A benchmark run fails when the server cannot keep up with requests. Should a framework fail at a given request rate on any infrastructure, its line is removed from the plot.
                To visualize result spread, the median (+) and average (x) latency of each run is also shown. These are not merged between separate infrastructures, so if a framework only fails on one infra, median latency on the other infras is still shown.
                </p>
                """);
        html.append("<div><dl>");

        loadGroup.emitFixedDiscriminators(html);

        html.append("</dl></div>");
        html.append("<div>");

        loadGroup.emitColoredDiscriminators(html);

        html.append("<label id='max-time'>Latency axis maximum: <input type='range' min='").append(Math.log10(minTime))
                .append("' max='").append(Math.log10(Duration.ofSeconds(2).toNanos()))
                .append("' value='").append(Math.log10(maxTime))
                .append("' oninput='updateMaxTime(Math.pow(10, this.value))' step='any'> <span></span></label>");
        if (asyncProfiler) {
            html.append("<label>CPU Usage Metric: ");
            cpuMetricSelector.emitSelect(html);
            html.append("</label>");
        }
        html.append("</div>");
        html.append("</div>");

        loadGroup.emitPhaseGraphs(html);

        html.append("</body></html>");
        return html.toString();
    }

    public static void main(String[] args) throws IOException {
        String html = new Main().plot();
        Path tmp = Paths.get("output/plot.html");
        Files.writeString(tmp, html);
        Runtime.getRuntime().exec(new String[]{"firefox", tmp.toString()});
    }

    record Discriminator(
            String name,
            Function<SuiteRunner.BenchmarkParameters, String> extractor,
            List<String> order,
            boolean selectWithDropdown
    ) {
        Discriminator(String name, Function<SuiteRunner.BenchmarkParameters, String> extractor) {
            this(name, extractor, List.of(), false);
        }

        Discriminator order(List<String> order) {
            return new Discriminator(name, extractor, order, selectWithDropdown);
        }

        Discriminator selectWithDropdown(boolean selectWithDropdown) {
            return new Discriminator(name, extractor, order, selectWithDropdown);
        }
    }

    record DiscriminatorLabel(
            List<String> values
    ) implements Comparable<DiscriminatorLabel> {
        @Override
        public int compareTo(DiscriminatorLabel o) {
            for (int i = 0; i < values.size(); i++) {
                String l = values.get(i);
                String r = o.values.get(i);
                List<String> order = DISCRIMINATORS.get(i).order;
                int cmp = Integer.compare(order.indexOf(l), order.indexOf(r));
                if (cmp != 0) {
                    return cmp;
                }
                cmp = l.compareTo(r);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }
    }
}
