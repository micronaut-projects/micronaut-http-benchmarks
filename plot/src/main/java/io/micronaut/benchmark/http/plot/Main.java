package io.micronaut.benchmark.http.plot;

import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.model.CreatePreauthenticatedRequestDetails;
import com.oracle.bmc.objectstorage.model.PreauthenticatedRequest;
import com.oracle.bmc.objectstorage.requests.CreatePreauthenticatedRequestRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import io.hyperfoil.http.statistics.HttpStats;
import io.micronaut.benchmark.api.BenchmarkResult;
import io.micronaut.benchmark.api.BenchmarkStats;
import one.jfr.JfrReader;
import one.jfr.event.CPULoad;
import one.jfr.event.Event;
import one.jfr.event.ExecutionSample;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Main {
    private final Path output;

    static final List<Discriminator> DISCRIMINATORS = List.of(
            new Discriminator("type", BenchmarkResult::type),
            new Discriminator("Hotspot options", p -> p.parameters() == null ? "N/A" : Objects.toString(((Map<?, Object>) p.parameters()).getOrDefault("hotspotOptions", "N/A"))),
            new Discriminator("Hotspot version", p -> {
                Map<?, ?> parameters = (Map<?, ?>) p.parameters();
                if (parameters == null || !parameters.containsKey("version")) {
                    return "N/A";
                }
                String version = Objects.toString(parameters.get("version"));
                if (parameters.containsKey("uri")) {
                    version = "<a href='" + parameters.get("uri") + "'>" + version + "</a>";
                }
                return version;
            }),
            new Discriminator("Request", p -> p.load().protocol().protocol().name() + " " + p.load().definition().method() + " " + p.load().definition().uri())
                    .selectWithDropdown(true),
            new Discriminator("Micronaut version", p -> compileConfiguration(p, "micronaut")),
            new Discriminator("JSON implementation", p -> compileConfiguration(p, "json")),
            new Discriminator("Netty transport", p -> compileConfiguration(p, "transport")),
            new Discriminator("tcnative support", p -> compileConfiguration(p, "tcnative")),
            new Discriminator("Threading", p -> threadingModel(p.parameters()))
                    .order(List.of("default", "event-loop", "io", "loom-carrier", "virtual")),
            new Discriminator("http client thread affinity mode", p -> compileConfiguration(p, "affinity"))
                    .order(List.of("enforced", "preferred", "off"))
    );

    private final ObjectMapper mapper = JsonMapper.builder()
            .registerSubtypes(HttpStats.class)
            .build();
    private final Map<String, BenchmarkStats> benchmarkOutput = new HashMap<>();
    private final double minTime;
    private final double maxTime = Duration.ofMillis(200).toNanos();
    private final List<BenchmarkResult> index;
    private final Map<BenchmarkResult, JfrSummary> jfrSummaries;
    private final Map<BenchmarkResult, ProfileConverter.ProfileArtifacts> profiles;

    private Main(Path output) throws IOException, InterruptedException {
        this.output = output.toAbsolutePath().normalize();
        index = new ArrayList<>(Results.index(this.output));
        index.sort(Comparator.comparing(BenchmarkResult::name));
        index.removeIf(p -> {
            BenchmarkStats statsAll = getBenchmark(p.name());
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
                .mapToDouble(BenchmarkStats.Percentile::to)
                .min().orElseThrow(() -> new IllegalArgumentException("No completed measurement data in " + output));

        profiles = new HashMap<>();
        jfrSummaries = new HashMap<>();
        for (BenchmarkResult parameters : index) {
            if (parameters.profiling() == null) {
                continue;
            }
            Path directory = output.resolve(parameters.name());
            ProfileConverter.ProfileArtifacts profile;
            try {
                profile = ProfileConverter.convert(directory, parameters.profiling());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            } catch (IOException | IllegalArgumentException exception) {
                System.err.println("Failed to convert profile for " + parameters.name() + ": " + exception.getMessage());
                profile = ProfileConverter.ProfileArtifacts.failed(parameters.profiling(), directory.resolve(parameters.profiling().artifact()));
            }
            profiles.put(parameters, profile);
            if (profile.available() && "async-profiler".equals(parameters.profiling().tool())) {
                JfrSummary summary = new JfrSummary();
                jfrSummaries.put(parameters, summary);
                try (JfrReader jfr = new JfrReader(profile.raw().toString())) {
                    while (true) {
                        Event event = jfr.readEvent();
                        if (event == null) {
                            break;
                        }
                        // from JfrToHeatmap
                        long msFromStart = (event.time - jfr.chunkStartTicks) * 1_000 / jfr.ticksPerSec;
                        Instant time = Instant.ofEpochMilli(jfr.chunkStartNanos / 1_000_000 + msFromStart);
                        BenchmarkStats.Stats phase = getBenchmark(parameters.name()).findPhaseContaining(time);

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
        }
    }

    private BenchmarkStats getBenchmark(String benchmarkName) {
        return benchmarkOutput.computeIfAbsent(benchmarkName, n -> {
            Path path = output.resolve(n).resolve("output.json");
            return mapper.readValue(path.toFile(), BenchmarkStats.class);
        });
    }

    @SuppressWarnings("unchecked")
    private static String compileConfiguration(BenchmarkResult parameters, String name) {
        Map<String, Object> map = (Map<String, Object>) parameters.parameters();
        if (map == null) {
            return "";
        }
        Map<String, Object> compileConfiguration = ((Map<String, Object>) map.get("compileConfiguration"));
        if (compileConfiguration == null) {
            return "";
        }
        Object v = compileConfiguration.get(name);
        return v == null ? "" : v.toString();
    }

    static String threadingModel(Object parameters) {
        if (!(parameters instanceof Map<?, ?> map)) {
            return "default";
        }
        String threading = Objects.toString(map.get("threading"), "default");
        return switch (threading) {
            case "default", "event-loop", "io", "virtual", "loom-carrier" -> threading;
            default -> throw new IllegalArgumentException("Unknown threading mode: " + map.get("threading"));
        };
    }

    static String loadStatic(String name) {
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
        if (!jfrSummaries.isEmpty()) {
            cpuMetricSelector = new DropdownSelector();
            metricAttributes = new EnumMap<>(CpuUsageMetric.class);
            for (CpuUsageMetric metric : CpuUsageMetric.values()) {
                metricAttributes.put(metric, cpuMetricSelector.addOption(metric.name()));
            }
            maxCpu = new HashMap<>();
            for (JfrSummary summary : jfrSummaries.values()) {
                for (JfrSummary.PhaseSummary phase : summary.phases.values()) {
                    for (CpuUsageMetric metric : CpuUsageMetric.values()) {
                        maxCpu.compute(metric, (ignored, v) -> Math.max(v == null ? 0 : v, phase.get(metric)));
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

        for (BenchmarkResult parameters : index) {
            loadGroup.add(
                    parameters,
                    getBenchmark(parameters.name()),
                    jfrSummaries.get(parameters),
                    profiles.get(parameters)
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
                """ + ChartEmitter.scripts() + """
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
        if (!jfrSummaries.isEmpty()) {
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

    public static void main(String[] args) throws Exception {
        generate(args.length == 0 ? Path.of("output") : Path.of(args[0]), args.length > 1 && args[1].equals("--upload"));
    }

    public static Path generate(Path directory, boolean upload) throws Exception {
        boolean throughput = ThroughputPlot.applicable(directory);
        Main main = throughput ? null : new Main(directory);
        var profiles = new ArrayList<ProfileConverter.ProfileArtifacts>();
        String html = throughput ? ThroughputPlot.render(directory, profiles) : main.plot();
        if (main != null) profiles.addAll(main.profiles.values());

        Path outputRoot = directory.toAbsolutePath().normalize();
        Path plotFile = outputRoot.resolve("plot.html");
        Files.writeString(plotFile, html);

        List<Path> resultFiles = new ArrayList<>();
        resultFiles.add(plotFile);
        for (ProfileConverter.ProfileArtifacts profile : profiles) {
            if (profile.raw() != null) {
                resultFiles.add(profile.raw());
            }
            if (profile.flamegraph() != null && Files.exists(profile.flamegraph())) {
                resultFiles.add(profile.flamegraph());
            }
            if (profile.reverseFlamegraph() != null && Files.exists(profile.reverseFlamegraph())) {
                resultFiles.add(profile.reverseFlamegraph());
            }
            if (profile.heatmap() != null && Files.exists(profile.heatmap())) {
                resultFiles.add(profile.heatmap());
            }
        }

        if (!upload) {
            return plotFile;
        }

        System.out.println("Creating plot.zip…");
        Path zipped = outputRoot.resolve("plot.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipped))) {
            for (Path file : resultFiles) {
                Path contained = requireOutputPath(outputRoot, file);
                zip.putNextEntry(new ZipEntry(outputRoot.relativize(contained).toString()));
                Files.copy(contained, zip);
            }
        }

        System.out.println("Uploading to bucket…");
        try (ObjectStorage os = ObjectStorageClient.builder()
                .build(new ConfigFileAuthenticationDetailsProvider((String) null))) {
            String prefix = Instant.now() + "/";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Path file : resultFiles) {
                Path contained = requireOutputPath(outputRoot, file);
                System.out.println("Uploading " + contained);
                byte[] bytes = Files.readAllBytes(contained);
                os.putObject(PutObjectRequest.builder()
                        .namespaceName("oraclelabs")
                        .bucketName("benchmark-results")
                        .objectName(prefix + outputRoot.relativize(contained))
                        .opcContentSha256(Base64.getEncoder().encodeToString(md.digest(bytes)))
                        .contentLength((long) bytes.length)
                        .putObjectBody(new ByteArrayInputStream(bytes))
                        .contentType(contained.toString().endsWith(".html") ? "text/html" : "application/octet-stream")
                        .build());
                md.reset();
            }

            PreauthenticatedRequest preauthenticatedRequest = os.createPreauthenticatedRequest(CreatePreauthenticatedRequestRequest.builder()
                    .namespaceName("oraclelabs")
                    .bucketName("benchmark-results")
                    .createPreauthenticatedRequestDetails(CreatePreauthenticatedRequestDetails.builder()
                            .name("Access to result set " + prefix)
                            .accessType(CreatePreauthenticatedRequestDetails.AccessType.AnyObjectRead)
                            .bucketListingAction(PreauthenticatedRequest.BucketListingAction.ListObjects)
                            .objectName(prefix)
                            .timeExpires(Date.from(Instant.now().plus(30, ChronoUnit.DAYS)))
                            .build())
                    .build()).getPreauthenticatedRequest();
            String uri = os.getEndpoint() + preauthenticatedRequest.getAccessUri() + prefix + outputRoot.relativize(plotFile);

            System.out.println("Result URI: " + uri);
            Runtime.getRuntime().exec(new String[]{"firefox", uri});
        }
        return plotFile;
    }

    private static Path requireOutputPath(Path outputRoot, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(outputRoot)) {
            throw new IllegalArgumentException("Result file escapes output directory: " + file);
        }
        return normalized;
    }

    record Discriminator(
            String name,
            Function<BenchmarkResult, String> extractor,
            List<String> order,
            boolean selectWithDropdown
    ) {
        Discriminator(String name, Function<BenchmarkResult, String> extractor) {
            this(name, extractor, List.of(), false);
        }

        Discriminator order(List<String> order) {
            return new Discriminator(name, extractor, order, selectWithDropdown);
        }

        Discriminator selectWithDropdown(boolean selectWithDropdown) {
            return new Discriminator(name, extractor, order, selectWithDropdown);
        }
    }
}
