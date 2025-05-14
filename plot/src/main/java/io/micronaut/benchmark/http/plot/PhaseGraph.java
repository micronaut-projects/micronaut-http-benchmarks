package io.micronaut.benchmark.http.plot;

import io.micronaut.benchmark.loadgen.oci.HyperfoilRunner;
import io.micronaut.core.annotation.Nullable;
import software.xdev.chartjs.model.charts.BarChart;
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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class PhaseGraph {
    private static final boolean COMBINE_HISTOGRAMS = true;
    private static final double MAX_PERCENTILE = 0.999;

    private final String phase;
    private final ScatterData scatterData = new ScatterData();
    private final MixedData mainData = new MixedData();
    private final Map<CpuUsageMetric, BarData> cpuData = new EnumMap<>(CpuUsageMetric.class);
    private int nextGroupIndex = 0;

    private boolean asyncProfiler = false;
    private Map<CpuUsageMetric, Double> maxCpu;
    private Map<CpuUsageMetric, DropdownSelector.OptionAttribute> metricAttributes;

    private double minTime;
    private double maxTime;

    private String title;

    PhaseGraph(String phase) {
        this.phase = phase;
    }

    public PhaseGraph time(double min, double max) {
        this.minTime = min;
        this.maxTime = max;
        return this;
    }

    public PhaseGraph maxCpu(Map<CpuUsageMetric, Double> maxCpu) {
        this.maxCpu = maxCpu;
        return this;
    }

    public PhaseGraph metricAttributes(Map<CpuUsageMetric, DropdownSelector.OptionAttribute> metricAttributes) {
        this.metricAttributes = metricAttributes;
        return this;
    }

    public PhaseGraph title(String title) {
        this.title = title;
        return this;
    }

    public Group addGroup() {
        return new Group();
    }

    public boolean isEmpty() {
        return mainData.getDatasets().isEmpty() && scatterData.getDatasets().isEmpty() && cpuData.values().stream().allMatch(d -> d.getDatasets().isEmpty());
    }

    public void emit(StringBuilder html, String htmlClass) {
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
        mainOptions.getPlugins().setTitle(new Title().setDisplay(true).setText(title));
        mainOptions.setLayout(new Layout().setPadding(0));
        MixedChart chart = new MixedChart()
                .setData(mainData)
                .setOptions(mainOptions);
        html.append("<div class='run-wrapper ").append(htmlClass).append("'><div class='run");
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
                .setMin(-0.5).setMax(nextGroupIndex)
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
                cpuOptions.getScales().addScale(Scales.ScaleAxis.Y, new LinearScaleOptions()
                        .setMin(0).setMax(maxCpu.get(metric)).setDisplay(false)
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
                        .wrapperClass(metricAttributes.get(metric).htmlClass())
                        .emit(html);
            }
        }
        html.append("</div></div>");
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

    class Group {
        private final int groupIndex = nextGroupIndex++;
        private List<HyperfoilRunner.StatsAll.Histogram> percentiles = COMBINE_HISTOGRAMS ? new ArrayList<>() : null;
        private final ScatterDataset medians = new ScatterDataset();
        private final ScatterDataset averages = new ScatterDataset();
        private String color;

        private Group() {
        }

        public Group color(String color) {
            this.color = color;
            return this;
        }

        public boolean add(HyperfoilRunner.StatsAll benchmark, @Nullable JfrSummary jfrSummary) {
            HyperfoilRunner.StatsAll.Stats stats = benchmark.findPhase(phase);
            if (stats == null) {
                percentiles = null;
                return false;
            }
            if (jfrSummary != null) {
                asyncProfiler = true;
                for (CpuUsageMetric metric : CpuUsageMetric.values()) {
                    BarData data = cpuData.computeIfAbsent(metric, k -> new BarData());
                    data.addLabel("");
                    double usage = jfrSummary.phase(stats).get(metric);
                    data.addDataset(new BarDataset()
                            .addData(usage)
                            .addBackgroundColor(color)
                            .setCategoryPercentage(1)
                            .setBarPercentage(0.8)
                            .setBarThickness("flex")
                    );
                }
            }
            if (benchmark.failures().stream().anyMatch(f -> f.phase().equals(phase))) {
                percentiles = null;
                return false;
            }
            if (COMBINE_HISTOGRAMS) {
                percentiles.add(stats.histogram());
            } else {
                addHistogram(mainData, stats.histogram(), color);
            }
            medians.addData(new ScatterDataPoint((double) groupIndex, stats.histogram()
                    .percentiles().stream()
                    .filter(p -> p.percentile() >= 0.5)
                    .mapToDouble(HyperfoilRunner.StatsAll.Percentile::to)
                    .findFirst().orElseThrow()));
            medians.addPointBorderColor(color);
            medians.addPointStyle("crossRot");
            medians.addPointBorderWidth(2);
            medians.addPointRadius(5);
            averages.addData(new ScatterDataPoint(groupIndex + 0.5, stats.total().summary().meanResponseTime));
            averages.addPointBorderColor(color);
            averages.addPointStyle("cross");
            averages.addPointBorderWidth(2);
            averages.addPointRadius(5);
            return true;
        }

        public void complete() {
            if (percentiles != null) {
                addHistogram(mainData, combineHistograms(percentiles), color);
            }
            if (!medians.getData().isEmpty()) {
                scatterData.addDataset(medians.withDefaultType());
                scatterData.addDataset(averages.withDefaultType());
            }
        }
    }
}
