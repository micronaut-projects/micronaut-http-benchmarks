package io.micronaut.benchmark.http.plot;

import io.micronaut.benchmark.api.BenchmarkResult;
import io.micronaut.benchmark.api.BenchmarkStats;
import io.micronaut.benchmark.api.InstanceType;
import io.micronaut.benchmark.api.ProtocolSettings;
import io.micronaut.core.annotation.Nullable;

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

    void add(BenchmarkResult parameters, BenchmarkStats result, @Nullable JfrSummary jfrSummary,
             @Nullable ProfileConverter.ProfileArtifacts profile) {
        index.add(new Entry(parameters, result, jfrSummary, profile));
    }

    private static DiscriminatorLabel getDiscriminator(BenchmarkResult p) {
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

    static String formatSut(InstanceType sut) {
        StringBuilder html = new StringBuilder()
                .append(sut.shape()).append(" ")
                .append(roundIfWhole(sut.ocpus())).append("CPU&nbsp;")
                .append(roundIfWhole(sut.memoryInGb())).append("G");
        if (sut.platform() != null && !sut.platform().isEmpty()) {
            html.append(" ").append(sut.platform());
        }
        if (sut.diskPerformanceUnits() != null) {
            html.append(" (").append(sut.diskPerformanceUnits()).append(" DPU)");
        }
        return html.toString();
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
                d.color = PlotColors.color(i, 1, 1);
            } else {
                double sv = 1, vv = 1;
                if (s != null) {
                    sv = 1 - (double) optionsByDiscriminator.get(s).indexOf(d.label.values().get(s)) / optionsByDiscriminator.get(s).size();
                }
                if (v != null) {
                    vv = 1 - (double) optionsByDiscriminator.get(v).indexOf(d.label.values().get(v)) / optionsByDiscriminator.get(v).size();
                }
                d.color = PlotColors.color(optionsByDiscriminator.get(h).indexOf(d.label.values().get(h)), sv, vv);
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
            InstanceType sut = index.getFirst().parameters.sutSpecs();
            html.append("<dt").append(htmlAttr()).append(">SUT</dt><dd").append(htmlAttr()).append(">")
                    .append(formatSut(sut))
                    .append("</dd>");
            if (index.stream().anyMatch(e -> e.profile != null && e.profile.available())) {
                html.append("<dt").append(htmlAttr()).append(">profiling</dt><dd class='warning ").append(htmlClass()).append("'>enabled</dd>");
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

        List<Integer> varying = new ArrayList<>();
        for (int i = 0; i < optionsByDiscriminator.size(); i++) {
            if (optionsByDiscriminator.get(i).size() != 1) {
                varying.add(i);
            }
        }
        if (varying.isEmpty()) {
            return;
        }
        // The last varying discriminator spans the columns, all others are nested row headers.
        Integer colDisc = varying.size() > 1 ? varying.getLast() : null;
        List<Integer> rowDiscs = colDisc == null ? varying : varying.subList(0, varying.size() - 1);
        List<List<String>> rows = discriminated.stream()
                .map(d -> rowDiscs.stream().map(j -> d.label.values().get(j)).toList())
                .distinct()
                .sorted((a, b) -> {
                    for (int k = 0; k < rowDiscs.size(); k++) {
                        List<String> opts = optionsByDiscriminator.get(rowDiscs.get(k));
                        int cmp = Integer.compare(opts.indexOf(a.get(k)), opts.indexOf(b.get(k)));
                        if (cmp != 0) {
                            return cmp;
                        }
                    }
                    return 0;
                })
                .toList();

        html.append("<table id='distinguisher-legend'").append(htmlAttr()).append('>');
        if (colDisc != null) {
            html.append("<tr><th colspan='").append(2 * rowDiscs.size()).append("'></th><th colspan='").append(optionsByDiscriminator.get(colDisc).size()).append("'>").append(DISCRIMINATORS.get(colDisc).name()).append("</th></tr>");
            html.append("<tr><th colspan='").append(2 * rowDiscs.size()).append("'></th>");
            for (String s : optionsByDiscriminator.get(colDisc)) {
                html.append("<th>").append(s).append("</th>");
            }
            html.append("</tr>");
        }
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            html.append("<tr>");
            for (int k = 0; k < rowDiscs.size(); k++) {
                if (i == 0) {
                    html.append("<th class='sideways' rowspan='").append(rows.size()).append("'><span>").append(DISCRIMINATORS.get(rowDiscs.get(k)).name()).append("</span></th>");
                }
                // merge header cells of consecutive rows that share this prefix
                if (i == 0 || !rows.get(i - 1).subList(0, k + 1).equals(row.subList(0, k + 1))) {
                    int span = 1;
                    while (i + span < rows.size() && rows.get(i + span).subList(0, k + 1).equals(row.subList(0, k + 1))) {
                        span++;
                    }
                    html.append("<th rowspan='").append(span).append("'>").append(row.get(k)).append("</th>");
                }
            }
            for (String colValue : colDisc == null ? List.of("") : optionsByDiscriminator.get(colDisc)) {
                Discriminated wrap = discriminated.stream()
                        .filter(d -> rowDiscs.stream().map(j -> d.label.values().get(j)).toList().equals(row)
                                && (colDisc == null || d.label.values().get(colDisc).equals(colValue)))
                        .findAny().orElse(null);
                if (wrap != null) {
                    html.append("<td style='background-color: ").append(wrap.color).append("' onclick='");
                    detailDialogSelector.emitSelectSpecific(html, wrap.detailDialogAttribute);
                    html.append("'></td>");
                } else {
                    html.append("<td></td>");
                }
            }
            html.append("</tr>");
        }
        html.append("</table>");
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
                    BenchmarkStats benchmark = entry.result;
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
                    if (entry.profile != null && entry.profile.available()) {
                        if (entry.profile.flamegraph() != null) {
                            html.append("<li><a href='").append(entry.parameters.name()).append("/flamegraph.html'>Flamegraph</a></li>");
                        }
                        if (entry.profile.reverseFlamegraph() != null) {
                            html.append("<li><a href='").append(entry.parameters.name()).append("/flamegraph-reverse.html'>Reverse flamegraph</a></li>");
                        }
                        if (entry.profile.heatmap() != null) {
                            html.append("<li><a href='").append(entry.parameters.name()).append("/heatmap.html'>Heatmap</a></li>");
                        }
                        html.append("<li><a href='").append(entry.parameters.name()).append('/').append(entry.profile.profiling().artifact()).append("'>Profile</a></li>");
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
     * @param profile    The converted profile artifacts, if any
     */
    private record Entry(
            BenchmarkResult parameters,
            BenchmarkStats result,
            @Nullable JfrSummary jfrSummary,
            @Nullable ProfileConverter.ProfileArtifacts profile
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
