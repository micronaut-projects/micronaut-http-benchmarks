package io.micronaut.benchmark.http.plot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.hyperfoil.http.statistics.HttpStats;
import io.micronaut.benchmark.loadgen.oci.HyperfoilRunner;
import io.micronaut.benchmark.loadgen.oci.ProtocolSettings;
import io.micronaut.benchmark.loadgen.oci.SuiteRunner;
import software.xdev.chartjs.model.charts.MixedChart;
import software.xdev.chartjs.model.data.MixedData;
import software.xdev.chartjs.model.datapoint.ScatterDataPoint;
import software.xdev.chartjs.model.dataset.LineDataset;
import software.xdev.chartjs.model.dataset.ScatterDataset;
import software.xdev.chartjs.model.javascript.JavaScriptFunction;
import software.xdev.chartjs.model.options.LegendOptions;
import software.xdev.chartjs.model.options.LineOptions;
import software.xdev.chartjs.model.options.Title;
import software.xdev.chartjs.model.options.animation.DefaultAnimation;
import software.xdev.chartjs.model.options.scale.Scales;
import software.xdev.chartjs.model.options.scale.cartesian.AbstractCartesianScaleOptions;
import software.xdev.chartjs.model.options.scale.cartesian.CartesianScaleOptions;
import software.xdev.chartjs.model.options.scale.cartesian.CartesianTickOptions;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Main {
    private static final Path OUTPUT = Path.of("output");

    private static final List<Discriminator> DISCRIMINATORS = List.of(
            new Discriminator("type", SuiteRunner.BenchmarkParameters::type),
            new Discriminator("Micronaut version", p -> compileConfiguration(p, "micronaut")),
            new Discriminator("JSON implementation", p -> compileConfiguration(p, "json")),
            new Discriminator("Netty transport", p -> compileConfiguration(p, "transport")),
            new Discriminator("tcnative support", p -> compileConfiguration(p, "tcnative")),
            new Discriminator("loom support", p -> compileConfiguration(p, "loom")),
            new Discriminator("http client thread affinity mode", p -> compileConfiguration(p, "affinity"))
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

    private Main() throws IOException {
        index = mapper.readValue(OUTPUT.resolve("index-full-loop.json").toFile(), new TypeReference<>() {
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
                .toList();

        for (int i = 0; i < discriminated.getFirst().values.size(); i++) {
            int finalI = i;
            optionsByDiscriminator.add(discriminated.stream().map(d -> d.values.get(finalI)).distinct().toList());
        }

        colors = selectColors(discriminated);
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

    private static double percentileTransform(double values) {
        return -Math.log10(1 - values);
    }

    private static double percentileTransformReverse(double values) {
        return 1 - Math.pow(10, -values);
    }

    private static String loadStatic(String name) {
        try (InputStream is = Main.class.getResourceAsStream(name)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
        html.append("<div><h3>Common settings</h3><dl>");
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
                    html.append("<th rowspan='").append(optionsByDiscriminator.get(rowDisc).size()).append("'>").append(DISCRIMINATORS.get(rowDisc).name).append("</th>");
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
        html.append("</div>");
        html.append("</div>");

        for (int phaseI = 0; phaseI < protocolSettings.ops().size(); phaseI++) {
            int ops = protocolSettings.ops().get(phaseI);
            String phase = "main/" + phaseI;
            MixedData mixedData = new MixedData();

            for (int i = 0; i < discriminated.size(); i++) {
                DiscriminatorLabel discriminator = discriminated.get(i);
                List<HyperfoilRunner.StatsAll.Histogram> percentiles = new ArrayList<>();
                ScatterDataset medians = new ScatterDataset();
                double medianX = percentileTransformReverse(percentileTransform(MAX_PERCENTILE) - (i + 1) * 0.1 - 0.05);
                ScatterDataset averages = new ScatterDataset();
                double averageX = percentileTransformReverse(percentileTransform(MAX_PERCENTILE) - (i + 1) * 0.1);
                String color = colors.get(discriminator);
                for (SuiteRunner.BenchmarkParameters parameters : index) {
                    if (!getDiscriminator(parameters).equals(discriminator)) {
                        continue;
                    }
                    HyperfoilRunner.StatsAll.Stats stats = getBenchmark(parameters.name()).findPhase(phase);
                    if (stats == null) {
                        percentiles = null;
                        break;
                    }
                    percentiles.add(stats.histogram());
                    medians.addData(new ScatterDataPoint(medianX, stats.histogram()
                            .percentiles().stream()
                            .filter(p -> p.percentile() >= 0.5)
                            .mapToDouble(HyperfoilRunner.StatsAll.Percentile::to)
                            .findFirst().orElseThrow()));
                    medians.addPointBorderColor(color);
                    medians.addPointStyle("crossRot");
                    averages.addData(new ScatterDataPoint(averageX, stats.total().summary().meanResponseTime));
                    averages.addPointBorderColor(color);
                    averages.addPointStyle("cross");
                }
                if (percentiles != null) {
                    LineDataset dataset = new LineDataset();
                    for (HyperfoilRunner.StatsAll.Percentile percentile : combineHistograms(percentiles).percentiles()) {
                        if (percentile.percentile() == 1.0) {
                            continue;
                        }
                        dataset.addDataUnchecked(List.of(percentile.percentile(), percentile.to()));
                        dataset.addPointRadius(0);
                    }
                    dataset.setBorderColor(color);
                    dataset.setBorderWidth(2);
                    mixedData.addDataset(dataset.withDefaultType());
                }
                if (!medians.getData().isEmpty()) {
                    mixedData.addDataset(medians.withDefaultType());
                    mixedData.addDataset(averages.withDefaultType());
                }
            }

            if (mixedData.getDatasets().isEmpty()) {
                continue;
            }

            LineOptions lineOptions = new LineOptions();
            lineOptions.setAnimation(new DefaultAnimation().setDuration(0));
            lineOptions.getScales().addScale(Scales.ScaleAxis.X, new CartesianScaleOptions("percentile")
                    .setMin(0).setMax(MAX_PERCENTILE)
            );
            lineOptions.getScales().addScale(Scales.ScaleAxis.Y, new CartesianScaleOptions("custom-log")
                    .setMin(minTime).setMax(maxTime)
                    .setTitle(new AbstractCartesianScaleOptions.Title().setDisplay(true).setText("Request latency"))
                    .setTicks(new CartesianTickOptions()
                            .setAutoSkip(false)
                            .setCallback(new JavaScriptFunction("formatYTicks")))
            );
            lineOptions.getPlugins().setLegend(new LegendOptions().setDisplay(false));
            lineOptions.getPlugins().setTitle(new Title().setDisplay(true).setText(ops + " ops/s"));
            MixedChart chart = new MixedChart()
                    .setData(mixedData)
                    .setOptions(lineOptions);
            html.append("<div class='run'><canvas id='c")
                    .append(phaseI)
                    .append("'></canvas><script>timedCharts.push(new Chart(document.getElementById('c")
                    .append(phaseI)
                    .append("').getContext('2d'), ")
                    .append(chart.toJson())
                    .append("))</script></div>");
        }

        html.append("</body></html>");
        return html.toString();
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

    private record Discriminator(
            String name,
            Function<SuiteRunner.BenchmarkParameters, String> extractor
    ) {
    }

    private record DiscriminatorLabel(
            List<String> values
    ) {
    }
}
