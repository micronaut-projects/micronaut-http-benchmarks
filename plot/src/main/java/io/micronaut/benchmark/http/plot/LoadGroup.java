package io.micronaut.benchmark.http.plot;

import io.micronaut.benchmark.loadgen.oci.Compute;
import io.micronaut.benchmark.loadgen.oci.HyperfoilRunner;
import io.micronaut.benchmark.loadgen.oci.ProtocolSettings;
import io.micronaut.benchmark.loadgen.oci.SuiteRunner;
import io.micronaut.core.annotation.Nullable;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.micronaut.benchmark.http.plot.Main.DISCRIMINATORS;

final class LoadGroup {
    private final boolean top;
    private final List<Entry> index = new ArrayList<>();

    private List<Discriminated> discriminated;
    private final List<List<String>> optionsByDiscriminator = new ArrayList<>();

    private double minTime;
    private double maxTime;

    private Map<CpuUsageMetric, Double> maxCpu;
    private Map<CpuUsageMetric, DropdownSelector.OptionAttribute> metricAttributes;

    private final List<DropdownSelector.OptionAttribute> dropdownSelectorAttributes;
    private int dropdownDiscriminatorIndex = -1;
    private DropdownSelector dropdownSelector;
    private List<LoadGroup> children;

    private final DropdownSelector detailDialogSelector;
    private final DropdownSelector.OptionAttribute detailDialogNone;

    public LoadGroup() {
        top = true;
        dropdownSelectorAttributes = List.of();
        detailDialogSelector = new DropdownSelector();
        detailDialogNone = detailDialogSelector.addOption(null);
    }

    private LoadGroup(LoadGroup parent, DropdownSelector.OptionAttribute attribute) {
        top = false;
        this.dropdownSelectorAttributes = Stream.concat(parent.dropdownSelectorAttributes.stream(), Stream.of(attribute)).toList();
        this.minTime = parent.minTime;
        this.maxTime = parent.maxTime;
        this.maxCpu = parent.maxCpu;
        this.metricAttributes = parent.metricAttributes;
        this.detailDialogSelector = parent.detailDialogSelector;
        this.detailDialogNone = parent.detailDialogNone;
    }

    LoadGroup time(double minTime, double maxTime) {
        this.minTime = minTime;
        this.maxTime = maxTime;
        return this;
    }

    LoadGroup maxCpu(Map<CpuUsageMetric, Double> maxCpu) {
        this.maxCpu = maxCpu;
        return this;
    }

    LoadGroup metricAttributes(Map<CpuUsageMetric, DropdownSelector.OptionAttribute> metricAttributes) {
        this.metricAttributes = metricAttributes;
        return this;
    }

    void add(SuiteRunner.BenchmarkParameters parameters, HyperfoilRunner.StatsAll result, @Nullable JfrSummary jfrSummary) {
        index.add(new Entry(parameters, result, jfrSummary));
    }

    private static DiscriminatorLabel getDiscriminator(SuiteRunner.BenchmarkParameters p) {
        return new DiscriminatorLabel(
                DISCRIMINATORS.stream().map(f -> f.extractor().apply(p)).toList());
    }

    private static String roundIfWhole(double d) {
        if (d == (int) d) {
            return (int) d + "";
        } else {
            return d + "";
        }
    }

    private void selectColors() {
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
        for (int i = 0; i < discriminated.size(); i++) {
            Discriminated d = discriminated.get(i);
            if (fallback) {
                d.color = "C" + i;
            } else {
                double hv, sv = 1, vv = 1;
                hv = List.of(278. / 255, 230. / 255, 157. / 255, 70. / 255).get(optionsByDiscriminator.get(h).indexOf(d.label.values().get(h)));
                if (s != null) {
                    sv = 1 - (double) optionsByDiscriminator.get(s).indexOf(d.label.values().get(s)) / optionsByDiscriminator.get(s).size();
                }
                if (v != null) {
                    vv = 1 - (double) optionsByDiscriminator.get(v).indexOf(d.label.values().get(v)) / optionsByDiscriminator.get(v).size();
                }
                Color color = Color.getHSBColor((float) hv, (float) sv, (float) vv);
                String hex = Integer.toHexString(color.getRGB() & 0xffffff);
                while (hex.length() < 6) {
                    //noinspection StringConcatenationInLoop
                    hex = "0" + hex;
                }
                d.color = "#" + hex;
            }
        }
    }

    void complete() {
        discriminated = index.stream()
                .map(Entry::parameters)
                .map(LoadGroup::getDiscriminator)
                .distinct()
                .sorted()
                .map(Discriminated::new)
                .toList();

        for (int i = 0; i < DISCRIMINATORS.size(); i++) {
            int finalI = i;
            Main.Discriminator discriminator = DISCRIMINATORS.get(i);
            List<String> opts = discriminated.stream().map(d -> d.label.values().get(finalI)).distinct()
                    .sorted(Comparator.comparingInt(discriminator.order()::indexOf))
                    .toList();
            optionsByDiscriminator.add(opts);
            if (opts.size() > 1 && discriminator.selectWithDropdown()) {
                dropdownSelector = new DropdownSelector();
                dropdownDiscriminatorIndex = i;
                children = new ArrayList<>(opts.size());
                for (String opt : opts) {
                    LoadGroup child = new LoadGroup(this, dropdownSelector.addOption(opt));
                    for (Entry entry : index) {
                        if (getDiscriminator(entry.parameters()).values().get(i).equals(opt)) {
                            child.index.add(entry);
                        }
                    }
                    child.complete();
                    children.add(child);
                }
                break;
            }
        }
        if (children == null) {
            selectColors();
        }
    }

    private String htmlClass() {
        return dropdownSelectorAttributes.stream()
                .map(DropdownSelector.OptionAttribute::htmlClass)
                .collect(Collectors.joining(" "));
    }

    private String htmlAttr() {
        if (dropdownSelectorAttributes.isEmpty()) {
            return "";
        } else {
            return " class=\"" + htmlClass() + "\"";
        }
    }

    void emitHead(StringBuilder html) {
        if (dropdownSelector != null) {
            dropdownSelector.emitHead(html);
        }
        if (children != null) {
            for (LoadGroup child : children) {
                child.emitHead(html);
            }
        }
        if (top) {
            detailDialogSelector.emitHead(html);
        }
    }

    void emitFixedDiscriminators(StringBuilder html) {
        emitFixedDiscriminators0(html, 0);
    }

    private void emitFixedDiscriminators0(StringBuilder html, int start) {
        for (int i = start; i < optionsByDiscriminator.size(); i++) {
            List<String> disc = optionsByDiscriminator.get(i);
            if (disc.size() == 1 && !disc.getFirst().isEmpty()) {
                html.append("<dt").append(htmlAttr()).append('>')
                        .append(DISCRIMINATORS.get(i).name())
                        .append("</dt><dd").append(htmlAttr()).append('>')
                        .append(disc.getFirst()).append("</dd>");
            } else if (i == dropdownDiscriminatorIndex) {
                html.append("<dt").append(htmlAttr()).append('>')
                        .append(DISCRIMINATORS.get(i).name())
                        .append("</dt><dd").append(htmlAttr()).append('>');
                dropdownSelector.emitSelect(html);
                html.append("</dd>");
            }
        }
        if (children == null) {
            Compute.ComputeConfiguration.InstanceType sut = index.getFirst().parameters.sutSpecs();
            html.append("<dt").append(htmlAttr()).append(">SUT</dt><dd").append(htmlAttr()).append(">")
                    .append(sut.shape()).append(" ")
                    .append(roundIfWhole(sut.ocpus())).append("CPU&nbsp;")
                    .append(roundIfWhole(sut.memoryInGb())).append("G ")
                    .append(sut.image())
                    .append("</dd>");
            if (index.stream().anyMatch(e -> e.jfrSummary != null)) {
                html.append("<dt").append(htmlAttr()).append(">async-profiler</dt><dd class='warning ").append(htmlClass()).append("'>enabled</dd>");
            }
        } else {
            for (LoadGroup child : children) {
                child.emitFixedDiscriminators0(html, optionsByDiscriminator.size());
            }
        }
    }

    void emitColoredDiscriminators(StringBuilder html) {
        if (children != null) {
            for (LoadGroup child : children) {
                child.emitColoredDiscriminators(html);
            }
            return;
        }

        Integer rowDisc = null, colDisc = null;
        for (int i = 0; i < optionsByDiscriminator.size(); i++) {
            List<String> disc = optionsByDiscriminator.get(i);
            if (disc.size() != 1) {
                if (rowDisc == null) {
                    rowDisc = i;
                } else if (colDisc == null) {
                    colDisc = i;
                } else {
                    throw new IllegalStateException();
                }
            }
        }
        if (rowDisc != null) {
            html.append("<table id='distinguisher-legend'").append(htmlAttr()).append('>');
            if (colDisc != null) {
                html.append("<tr><th colspan='2'></th><th colspan='").append(optionsByDiscriminator.get(colDisc).size()).append("'>").append(DISCRIMINATORS.get(colDisc).name()).append("</th></tr>");
                html.append("<tr><th colspan='2'></th>");
                for (String s : optionsByDiscriminator.get(colDisc)) {
                    html.append("<th>").append(s).append("</th>");
                }
                html.append("</tr>");
            }
            for (int i = 0; i < optionsByDiscriminator.get(rowDisc).size(); i++) {
                html.append("<tr>");
                if (i == 0) {
                    html.append("<th class='sideways' rowspan='").append(optionsByDiscriminator.get(rowDisc).size()).append("'><span>").append(DISCRIMINATORS.get(rowDisc).name()).append("</span></th>");
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
                    Discriminated wrap = discriminated.stream().filter(d -> d.label.values.equals(disc)).findAny().orElseThrow();
                    html.append("<td style='background-color: ").append(wrap.color).append("' onclick='");
                    detailDialogSelector.emitSelectSpecific(html, wrap.detailDialogAttribute);
                    html.append("'></td>");
                }
                html.append("</tr>");
            }
            html.append("</table>");
        }
    }

    void emitPhaseGraphs(StringBuilder html) {
        if (children != null) {
            for (LoadGroup child : children) {
                child.emitPhaseGraphs(html);
            }
            return;
        }

        ProtocolSettings protocolSettings = index.getFirst().parameters.load().protocol();

        for (int phaseI = 0; phaseI < protocolSettings.ops().size(); phaseI++) {
            PhaseGraph phaseGraph = new PhaseGraph("main/" + phaseI)
                    .title(((int) protocolSettings.ops().get(phaseI)) + " ops/s")
                    .time(minTime, maxTime)
                    .maxCpu(maxCpu)
                    .metricAttributes(metricAttributes);

            for (Discriminated d : discriminated) {
                PhaseGraph.Group group = phaseGraph.addGroup().color(d.color);
                for (Entry entry : index) {
                    if (!getDiscriminator(entry.parameters).equals(d.label)) {
                        continue;
                    }
                    HyperfoilRunner.StatsAll benchmark = entry.result;
                    if (!group.add(benchmark, entry.jfrSummary)) {
                        break;
                    }
                }
                group.complete();
            }

            if (!phaseGraph.isEmpty()) {
                phaseGraph.emit(html, htmlClass());
            }
        }

        for (Discriminated d : discriminated) {
            html.append("<div class='dialog ").append(d.detailDialogAttribute.htmlClass()).append("'>");
            html.append("<div onclick='if (this === event.target) ");
            detailDialogSelector.emitSelectSpecific(html, detailDialogNone);
            html.append("'><div>");
            for (Entry entry : index) {
                if (getDiscriminator(entry.parameters).equals(d.label)) {
                    html.append("<h3>").append(entry.parameters.name()).append("</h3><ul>");
                    if (entry.jfrSummary != null) {
                        html.append("<li><a href='").append(entry.parameters.name()).append("/flamegraph.html'>Flamegraph</a></li>");
                        html.append("<li><a href='").append(entry.parameters.name()).append("/heatmap.html'>Heatmap</a></li>");
                    }
                    html.append("</ul>");
                }
            }
            html.append("</dl></div></div></div>");
        }
    }

    /**
     * A single benchmark run, with a specific set of benchmark parameters.
     *
     * @param parameters The parameters
     * @param result     The hyperfoil result
     * @param jfrSummary The summary of the collected JFR file, if any
     */
    private record Entry(
            SuiteRunner.BenchmarkParameters parameters,
            HyperfoilRunner.StatsAll result,
            @Nullable JfrSummary jfrSummary
    ) {
    }

    /**
     * A set of {@link Entry entries} with the same {@link DiscriminatorLabel}, e.g. the same benchmark parameters on
     * different infrastructures.
     */
    private class Discriminated {
        private final DiscriminatorLabel label;
        private String color;
        private final DropdownSelector.OptionAttribute detailDialogAttribute = detailDialogSelector.addOption(null);

        Discriminated(DiscriminatorLabel label) {
            this.label = label;
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
                List<String> order = DISCRIMINATORS.get(i).order();
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
