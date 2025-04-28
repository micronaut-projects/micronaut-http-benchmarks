package io.micronaut.benchmark.http.plot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.hyperfoil.http.statistics.HttpStats;
import io.micronaut.benchmark.loadgen.oci.Compute;
import io.micronaut.benchmark.loadgen.oci.HyperfoilRunner;
import io.micronaut.benchmark.loadgen.oci.ProtocolSettings;
import io.micronaut.benchmark.loadgen.oci.SuiteRunner;
import one.jfr.JfrReader;
import one.jfr.event.CPULoad;
import one.jfr.event.Event;
import one.jfr.event.ExecutionSample;
import software.xdev.chartjs.model.charts.BarChart;
import software.xdev.chartjs.model.charts.Chart;
import software.xdev.chartjs.model.charts.MixedChart;
import software.xdev.chartjs.model.charts.ScatterChart;
import software.xdev.chartjs.model.data.BarData;
import software.xdev.chartjs.model.data.MixedData;
import software.xdev.chartjs.model.data.ScatterData;
import software.xdev.chartjs.model.datapoint.ScatterDataPoint;
import software.xdev.chartjs.model.dataset.BarDataset;
import software.xdev.chartjs.model.dataset.LineDataset;
import software.xdev.chartjs.model.dataset.ScatterDataset;
import software.xdev.chartjs.model.javascript.JavaScriptFunction;
import software.xdev.chartjs.model.options.BarOptions;
import software.xdev.chartjs.model.options.LegendOptions;
import software.xdev.chartjs.model.options.LineOptions;
import software.xdev.chartjs.model.options.Title;
import software.xdev.chartjs.model.options.animation.DefaultAnimation;
import software.xdev.chartjs.model.options.layout.Layout;
import software.xdev.chartjs.model.options.scale.GridLineConfiguration;
import software.xdev.chartjs.model.options.scale.Scales;
import software.xdev.chartjs.model.options.scale.cartesian.AbstractCartesianScaleOptions;
import software.xdev.chartjs.model.options.scale.cartesian.BorderConfiguration;
import software.xdev.chartjs.model.options.scale.cartesian.CartesianScaleOptions;
import software.xdev.chartjs.model.options.scale.cartesian.CartesianTickOptions;
import software.xdev.chartjs.model.options.scale.cartesian.linear.LinearScaleOptions;
import software.xdev.chartjs.model.options.scale.cartesian.linear.LinearTickOptions;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Main {
    private static final Path OUTPUT = Path.of("output");

    private static final boolean COMBINE_HISTOGRAMS = true;

    private static final List<Discriminator> DISCRIMINATORS = List.of(
            new Discriminator("type", SuiteRunner.BenchmarkParameters::type),
            new Discriminator("Hotspot options", p -> ((Map<?, ?>) p.parameters()).get("hotspotOptions").toString()),
            new Discriminator("Request", p -> p.load().protocol().protocol().name() + " " + p.load().definition().getMethod() + " " + p.load().definition().getUri()),
            new Discriminator("Micronaut version", p -> compileConfiguration(p, "micronaut")),
            new Discriminator("JSON implementation", p -> compileConfiguration(p, "json")),
            new Discriminator("Netty transport", p -> compileConfiguration(p, "transport")),
            new Discriminator("tcnative support", p -> compileConfiguration(p, "tcnative")),
            new Discriminator("loom support", p -> compileConfiguration(p, "loom"), List.of("off", "carried", "on")),
            new Discriminator("http client thread affinity mode", p -> compileConfiguration(p, "affinity"), List.of("enforced", "preferred", "off"))
    );
    private static final double MAX_PERCENTILE = 0.999;

    private final ObjectMapper mapper = JsonMapper.builder()
            .registerSubtypes(HttpStats.class)
            .build();
    private final Map<String, HyperfoilRunner.StatsAll> benchmarkOutput = new HashMap<>();
    private final ProtocolSettings protocolSettings;
    private final double minTime;
    private final double maxTime = Duration.ofMillis(200).toNanos();
    private final List<DiscriminatorLabel> discriminated;
    private final List<List<String>> optionsByDiscriminator = new ArrayList<>();
    private final Map<DiscriminatorLabel, String> colors;
    private final List<SuiteRunner.BenchmarkParameters> index;
    private final boolean asyncProfiler;
    private final Map<SuiteRunner.BenchmarkParameters, JfrSummary> jfrSummaries;
    private int chartIndex;

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
        protocolSettings = index.getFirst().load().protocol();

        minTime = index.stream()
                .map(p -> getBenchmark(p.name()))
                .flatMap(s -> s.stats().stream())
                .flatMap(s -> s.histogram().percentiles().stream())
                .mapToDouble(HyperfoilRunner.StatsAll.Percentile::to)
                .min().orElseThrow();

        discriminated = index.stream()
                .map(Main::getDiscriminator)
                .distinct()
                .sorted()
                .toList();

        for (int i = 0; i < discriminated.getFirst().values.size(); i++) {
            int finalI = i;
            optionsByDiscriminator.add(discriminated.stream().map(d -> d.values.get(finalI)).distinct()
                    .sorted(Comparator.comparingInt(DISCRIMINATORS.get(i).order::indexOf))
                    .toList());
        }

        colors = selectColors(discriminated);

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

    private static DiscriminatorLabel getDiscriminator(SuiteRunner.BenchmarkParameters p) {
        return new DiscriminatorLabel(
                DISCRIMINATORS.stream().map(f -> f.extractor.apply(p)).toList());
    }

    private Map<DiscriminatorLabel, String> selectColors(List<DiscriminatorLabel> discriminated) {
        Integer h = null, s = null, v = null;
        boolean fallback = false;
        for (int i = 0; i < optionsByDiscriminator.size(); i++) {
            if (optionsByDiscriminator.get(i).size() <= 1) {
                continue;
            }
            if (h == null) {
                h = i;
            } else if (s == null) {
                s = i;
            } else if (v == null) {
                v = i;
            } else {
                fallback = true;
            }
        }
        if (h == null) {
            fallback = true;
        }
        Map<DiscriminatorLabel, String> map = new HashMap<>();
        for (DiscriminatorLabel discriminator : discriminated) {
            if (fallback) {
                map.put(discriminator, "C" + map.size());
            } else {
                double hv, sv = 1, vv = 1;
                hv = List.of(278. / 255, 230. / 255, 157. / 255).get(optionsByDiscriminator.get(h).indexOf(discriminator.values.get(h)));
                if (s != null) {
                    sv = 1 - (double) optionsByDiscriminator.get(s).indexOf(discriminator.values.get(s)) / optionsByDiscriminator.get(s).size();
                }
                if (v != null) {
                    vv = 1 - (double) optionsByDiscriminator.get(v).indexOf(discriminator.values.get(v)) / optionsByDiscriminator.get(v).size();
                }
                Color color = Color.getHSBColor((float) hv, (float) sv, (float) vv);
                String hex = Integer.toHexString(color.getRGB() & 0xffffff);
                while (hex.length() < 6) {
                    //noinspection StringConcatenationInLoop
                    hex = "0" + hex;
                }
                map.put(discriminator, "#" + hex);
            }
        }
        return map;
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

    private static String roundIfWhole(double d) {
        if (d == (int) d) {
            return (int) d + "";
        } else {
            return d + "";
        }
    }

    String plot() {
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
                </head>
                <body>
                """);

        html.append("<div id='legend'>");
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
        Integer rowDisc = null, colDisc = null;
        for (int i = 0; i < optionsByDiscriminator.size(); i++) {
            List<String> disc = optionsByDiscriminator.get(i);
            if (disc.size() == 1) {
                if (!disc.getFirst().isEmpty()) {
                    html.append("<dt>").append(DISCRIMINATORS.get(i).name).append("</dt><dd>").append(disc.getFirst()).append("</dd>");
                }
            } else {
                if (rowDisc == null) {
                    rowDisc = i;
                } else if (colDisc == null) {
                    colDisc = i;
                } else {
                    throw new IllegalStateException();
                }
            }
        }
        Compute.ComputeConfiguration.InstanceType sut = index.getFirst().sutSpecs();
        html.append("<dt>SUT</dt><dd>")
                .append(sut.shape()).append(" ")
                .append(roundIfWhole(sut.ocpus())).append("CPU&nbsp;")
                .append(roundIfWhole(sut.memoryInGb())).append("G ")
                .append(sut.image())
                .append("</dd>");
        if (asyncProfiler) {
            html.append("<dt>async-profiler</dt><dd class='warning'>enabled</dd>");
        }
        html.append("</dl></div>");
        html.append("<div>");
        if (rowDisc != null) {
            html.append("<table id='distinguisher-legend'>");
            if (colDisc != null) {
                html.append("<tr><th colspan='2'></th><th colspan='").append(optionsByDiscriminator.get(colDisc).size()).append("'>").append(DISCRIMINATORS.get(colDisc).name).append("</th></tr>");
                html.append("<tr><th colspan='2'></th>");
                for (String s : optionsByDiscriminator.get(colDisc)) {
                    html.append("<th>").append(s).append("</th>");
                }
                html.append("</tr>");
            }
            for (int i = 0; i < optionsByDiscriminator.get(rowDisc).size(); i++) {
                html.append("<tr>");
                if (i == 0) {
                    html.append("<th class='sideways' rowspan='").append(optionsByDiscriminator.get(rowDisc).size()).append("'><span>").append(DISCRIMINATORS.get(rowDisc).name).append("</span></th>");
                }
                html.append("<th>").append(optionsByDiscriminator.get(rowDisc).get(i)).append("</th>");
                for (String colValue : colDisc == null ? List.of("") : optionsByDiscriminator.get(colDisc)) {
                    List<String> disc = new ArrayList<>(DISCRIMINATORS.size());
                    for (int j = 0; j < DISCRIMINATORS.size(); j++) {
                        if (j == rowDisc) {
                            disc.add(optionsByDiscriminator.get(rowDisc).get(i));
                        } else if (colDisc != null && j == colDisc) {
                            disc.add(colValue);
                        } else {
                            disc.add(optionsByDiscriminator.get(j).getFirst());
                        }
                    }
                    html.append("<td style='background-color: ").append(colors.get(new DiscriminatorLabel(disc))).append("'></td>");
                }
                html.append("</tr>");
            }
            html.append("</table>");
        }
        html.append("<label id='max-time'>Latency axis maximum: <input type='range' min='").append(Math.log10(minTime))
                .append("' max='").append(Math.log10(Duration.ofSeconds(2).toNanos()))
                .append("' value='").append(Math.log10(maxTime))
                .append("' oninput='updateMaxTime(Math.pow(10, this.value))' step='any'> <span></span></label>");
        if (asyncProfiler) {
            html.append("<label>CPU Usage Metric: <select id='cpu-usage-metric-select' onchange='setCpuUsageMetric(this.value)'>");
            for (CpuUsageMetric metric : CpuUsageMetric.values()) {
                html.append("<option value='cpu-usage-metric-").append(metric.name()).append("'>").append(metric.name()).append("</option>");
            }
            html.append("</select></label>");
        }
        html.append("</div>");
        html.append("</div>");

        for (int phaseI = 0; phaseI < protocolSettings.ops().size(); phaseI++) {
            int ops = protocolSettings.ops().get(phaseI);
            String phase = "main/" + phaseI;
            ScatterData scatterData = new ScatterData();
            MixedData mainData = new MixedData();
            Map<CpuUsageMetric, BarData> cpuData = new EnumMap<>(CpuUsageMetric.class);

            for (int i = 0; i < discriminated.size(); i++) {
                DiscriminatorLabel discriminator = discriminated.get(i);
                List<HyperfoilRunner.StatsAll.Histogram> percentiles = COMBINE_HISTOGRAMS ? new ArrayList<>() : null;
                ScatterDataset medians = new ScatterDataset();
                ScatterDataset averages = new ScatterDataset();
                String color = colors.get(discriminator);
                for (SuiteRunner.BenchmarkParameters parameters : index) {
                    if (!getDiscriminator(parameters).equals(discriminator)) {
                        continue;
                    }
                    HyperfoilRunner.StatsAll benchmark = getBenchmark(parameters.name());
                    HyperfoilRunner.StatsAll.Stats stats = benchmark.findPhase(phase);
                    if (stats == null) {
                        percentiles = null;
                        break;
                    }
                    if (asyncProfiler) {
                        for (CpuUsageMetric metric : CpuUsageMetric.values()) {
                            BarData data = cpuData.computeIfAbsent(metric, k -> new BarData());
                            data.addLabel("");
                            data.addDataset(new BarDataset()
                                    .addData(jfrSummaries.get(parameters).phase(stats).get(metric))
                                    .addBackgroundColor(color)
                                    .setCategoryPercentage(1)
                                    .setBarPercentage(0.8)
                                    .setBarThickness("flex")
                            );
                        }
                    }
                    if (benchmark.failures().stream().anyMatch(f -> f.phase().equals(phase))) {
                        percentiles = null;
                        break;
                    }
                    if (COMBINE_HISTOGRAMS) {
                        percentiles.add(stats.histogram());
                    } else {
                        addHistogram(mainData, stats.histogram(), color);
                    }
                    medians.addData(new ScatterDataPoint((double) i, stats.histogram()
                            .percentiles().stream()
                            .filter(p -> p.percentile() >= 0.5)
                            .mapToDouble(HyperfoilRunner.StatsAll.Percentile::to)
                            .findFirst().orElseThrow()));
                    medians.addPointBorderColor(color);
                    medians.addPointStyle("crossRot");
                    medians.addPointBorderWidth(2);
                    medians.addPointRadius(5);
                    averages.addData(new ScatterDataPoint(i + 0.5, stats.total().summary().meanResponseTime));
                    averages.addPointBorderColor(color);
                    averages.addPointStyle("cross");
                    averages.addPointBorderWidth(2);
                    averages.addPointRadius(5);
                }
                if (percentiles != null) {
                    addHistogram(mainData, combineHistograms(percentiles), color);
                }
                if (!medians.getData().isEmpty()) {
                    scatterData.addDataset(medians.withDefaultType());
                    scatterData.addDataset(averages.withDefaultType());
                }
            }

            if (mainData.getDatasets().isEmpty() && scatterData.getDatasets().isEmpty() && cpuData.values().stream().allMatch(d -> d.getDatasets().isEmpty())) {
                continue;
            }

            LineOptions mainOptions = new LineOptions();
            mainOptions.setAnimation(new DefaultAnimation().setDuration(0));
            mainOptions.getScales().addScale(Scales.ScaleAxis.X, new CartesianScaleOptions("percentile")
                    .setBorder(new BorderConfiguration().setDisplay(false))
                            .setTicks(new CartesianTickOptions().setAlign("end"))
                    .setMin(0).setMax(MAX_PERCENTILE));
            mainOptions.getScales().addScale(Scales.ScaleAxis.Y, new CartesianScaleOptions("custom-log")
                    .setMin(minTime).setMax(maxTime)
                    .setTitle(new AbstractCartesianScaleOptions.Title().setDisplay(true).setText("Request latency"))
                    .setBorder(new BorderConfiguration().setDisplay(false))
                    .setTicks(new CartesianTickOptions()
                            .setAutoSkip(false)
                            .setCallback(new JavaScriptFunction("formatYTicks"))));
            mainOptions.getPlugins().setLegend(new LegendOptions().setDisplay(false));
            mainOptions.getPlugins().setTitle(new Title().setDisplay(true).setText(ops + " ops/s"));
            mainOptions.setLayout(new Layout().setPadding(0));
            MixedChart chart = new MixedChart()
                    .setData(mainData)
                    .setOptions(mainOptions);
            html.append("<div class='run-wrapper'><div class='run");
            if (asyncProfiler) {
                html.append(" async-profiler");
            }
            html.append("'>");
            new ChartEmitter(chart).collection("timedCharts").emit(html);
            LineOptions scatterOptions = new LineOptions();
            scatterOptions.setMaintainAspectRatio(false);
            scatterOptions.getPlugins().setLegend(new LegendOptions().setDisplay(false));
            scatterOptions.setAnimation(new DefaultAnimation().setDuration(0));
            scatterOptions.getPlugins().setTitle(new Title().setDisplay(true).setText(""));
            scatterOptions.getScales().addScale(Scales.ScaleAxis.X, new LinearScaleOptions()
                    .setMin(-0.5).setMax(discriminated.size())
                    .setGrid(new GridLineConfiguration().setDisplay(false))
                    .setBorder(new BorderConfiguration().setDisplay(false))
                    .setTicks(new LinearTickOptions().setCallback(new JavaScriptFunction("noTicks"))));
            scatterOptions.getScales().addScale(Scales.ScaleAxis.Y, new CartesianScaleOptions("custom-log")
                    .setMin(minTime).setMax(maxTime)
                    .setBorder(new BorderConfiguration().setDisplay(false))
                    .setTicks(new CartesianTickOptions().setAutoSkip(false).setDisplay(false)));
            scatterOptions.setLayout(new Layout().setPadding(0));
            new ChartEmitter(new ScatterChart()
                    .setData(scatterData)
                    .setOptions(scatterOptions)
            ).collection("timedCharts").emit(html);
            if (asyncProfiler) {
                for (CpuUsageMetric metric : CpuUsageMetric.values()) {
                    BarOptions cpuOptions = new BarOptions();
                    cpuOptions.setMaintainAspectRatio(false);
                    cpuOptions.getPlugins().setLegend(new LegendOptions().setDisplay(false));
                    cpuOptions.setAnimation(new DefaultAnimation().setDuration(0));
                    cpuOptions.getPlugins().setTitle(new Title().setDisplay(true).setText("CPU"));
                    double maxCpu = jfrSummaries.values().stream()
                            .flatMap(s -> s.phases.values().stream())
                            .mapToDouble(s -> s.get(metric))
                            .max().orElseThrow();
                    cpuOptions.getScales().addScale(Scales.ScaleAxis.Y, new LinearScaleOptions()
                            .setMin(0).setMax(maxCpu).setDisplay(false)
                            .setBorder(new BorderConfiguration().setDisplay(false))
                            .setTicks(new LinearTickOptions().setDisplay(false))
                            .setGrid(new GridLineConfiguration().setDisplay(false).setDrawTicks(false))
                    );
                    cpuOptions.getScales().addScale(Scales.ScaleAxis.X, new LinearScaleOptions()
                            .setDisplay(false)
                            .setBorder(new BorderConfiguration().setDisplay(false))
                            .setTicks(new LinearTickOptions().setDisplay(false).setPadding(0))
                            .setGrid(new GridLineConfiguration().setDisplay(false).setDrawTicks(false))
                    );
                    cpuOptions.setLayout(new Layout().setPadding(0));
                    new ChartEmitter(new BarChart()
                            .setData(cpuData.get(metric))
                            .setOptions(cpuOptions))
                            .wrapperClass("cpu-usage-metric cpu-usage-metric-" + metric)
                            .emit(html);
                }
            }
            html.append("</div></div>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    private static void addHistogram(MixedData destPlot, HyperfoilRunner.StatsAll.Histogram histogram, String color) {
        LineDataset dataset = new LineDataset();
        for (HyperfoilRunner.StatsAll.Percentile percentile : histogram.percentiles()) {
            if (percentile.percentile() == 1.0) {
                continue;
            }
            dataset.addDataUnchecked(List.of(percentile.percentile(), percentile.to()));
            dataset.addPointRadius(0);
        }
        dataset.setBorderColor(color);
        dataset.setBorderWidth(2);
        destPlot.addDataset(dataset.withDefaultType());
    }

    private static HyperfoilRunner.StatsAll.Histogram combineHistograms(List<HyperfoilRunner.StatsAll.Histogram> histograms) {
        List<Double> bucketTo = histograms.stream()
                .flatMap(h -> h.percentiles().stream())
                .map(HyperfoilRunner.StatsAll.Percentile::to)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        bucketTo.addFirst(0.0);
        long[] bucketCount = new long[bucketTo.size()];
        long totalCount = 0;
        for (HyperfoilRunner.StatsAll.Histogram histogram : histograms) {
            for (HyperfoilRunner.StatsAll.Percentile percentile : histogram.percentiles()) {
                int fromBucket = bucketTo.indexOf(percentile.from());
                int toBucket = bucketTo.indexOf(percentile.to());
                totalCount += percentile.count();
                for (int i = fromBucket + 1; i <= toBucket; i++) {
                    bucketCount[i] += (long) (percentile.count() * (bucketTo.get(i) - bucketTo.get(i - 1)) / (percentile.to() - percentile.from()));
                }
            }
        }
        assert bucketCount[0] == 0;
        List<HyperfoilRunner.StatsAll.Percentile> out = new ArrayList<>();
        long countSoFar = 0;
        for (int i = 1; i < bucketTo.size(); i++) {
            countSoFar += bucketCount[i];
            out.add(new HyperfoilRunner.StatsAll.Percentile(
                    bucketTo.get(i - 1),
                    bucketTo.get(i),
                    (double) countSoFar / totalCount,
                    bucketCount[i],
                    countSoFar
            ));
        }
        return new HyperfoilRunner.StatsAll.Histogram(out);
    }

    public static void main(String[] args) throws IOException {
        String html = new Main().plot();
        Path tmp = Paths.get("output/plot.html");
        Files.writeString(tmp, html);
        Runtime.getRuntime().exec(new String[]{"firefox", tmp.toString()});
    }

    private final class ChartEmitter {
        private final Chart<?, ?, ?> chart;
        private String collection = null;
        private String wrapperClass = "";

        private ChartEmitter(Chart<?, ?, ?> chart) {
            this.chart = chart;
        }

        public ChartEmitter collection(String collection) {
            this.collection = collection;
            return this;
        }

        public ChartEmitter wrapperClass(String wrapperClass) {
            this.wrapperClass = wrapperClass;
            return this;
        }

        void emit(StringBuilder html) {
            int i = chartIndex++;
            html.append("<div class='").append(wrapperClass).append("'><canvas id='c").append(i).append("'></canvas><script>");
            if (collection != null) {
                html.append(collection).append(".push(");
            }
            html.append("new Chart(document.getElementById('c")
                    .append(i)
                    .append("').getContext('2d'), ")
                    .append(chart.toJson())
                    .append(")");
            if (collection != null) {
                html.append(")");
            }
            html.append("</script></div>");
        }
    }

    private record Discriminator(
            String name,
            Function<SuiteRunner.BenchmarkParameters, String> extractor,
            List<String> order
    ) {
        Discriminator(String name, Function<SuiteRunner.BenchmarkParameters, String> extractor) {
            this(name, extractor, List.of());
        }
    }

    private record DiscriminatorLabel(
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

    private static final class JfrSummary {
        final Map<String, PhaseSummary> phases = new HashMap<>();

        PhaseSummary phase(HyperfoilRunner.StatsAll.Stats phase) {
            return phases.computeIfAbsent(phase.name(), k -> new PhaseSummary(Duration.ofNanos(phase.total().summary().endTime - phase.total().summary().startTime)));
        }
    }

    private static final class PhaseSummary {
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

    private enum CpuUsageMetric {
        EXECUTION_SAMPLES,
        JVM_USER,
        JVM_SYSTEM,
        JVM,
        MACHINE_TOTAL,
    }
}
