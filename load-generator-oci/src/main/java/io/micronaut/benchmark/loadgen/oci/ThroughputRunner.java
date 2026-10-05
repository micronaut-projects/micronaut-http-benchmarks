package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.api.BenchmarkStats;
import io.micronaut.benchmark.api.ThroughputResult;
import io.micronaut.benchmark.api.ThroughputSearch;
import io.micronaut.benchmark.api.ThroughputStage;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One repetition is an indivisible discovery/reset/validation pair in the daemon's batch schedule. */
final class ThroughputRunner {
    // Bound draining after injection ends, including sessions stuck waiting for a connection.
    // This is longer than the native 30-second request timeout and does not shorten measurement.
    private static final long DRAIN_TIMEOUT_MILLIS = 120_000;
    private final JsonMapper mapper;

    ThroughputRunner(JsonMapper mapper) {
        this.mapper = mapper.rebuild().registerSubtypes(io.hyperfoil.http.statistics.HttpStats.class).build();
    }

    void repetition(ExecutionEnvironment environment, PreparedExperiment experiment, Path root, int repetition,
                    PhaseTracker.PhaseUpdater progress) throws Exception {
        ThroughputSearch search = experiment.search();
        String discoveryPath = "repetitions/" + repetition + "/discovery";
        ThroughputStage.Result discovery;
        try {
            discovery = stage(environment, experiment, root, discoveryPath, search.discovery(), progress);
        } catch (Exception e) {
            save(root, search, new ThroughputResult.Repetition(repetition, discoveryPath,
                    search.discovery().invalid("Discovery execution failed: " + e), null, null));
            throw e;
        }
        var entry = new ThroughputResult.Repetition(repetition, discoveryPath, discovery, null, null);
        save(root, search, entry);
        if (!discovery.canValidate()) return;
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Search cancelled between stages");
        String validationPath = "repetitions/" + repetition + "/validation";
        try {
            var validation = stage(environment, experiment, root, validationPath, search.validation(discovery), progress);
            save(root, search, new ThroughputResult.Repetition(repetition, discoveryPath, discovery, validationPath, validation));
        } catch (Exception e) {
            save(root, search, new ThroughputResult.Repetition(repetition, discoveryPath, discovery, validationPath,
                    search.validation(discovery).invalid("Validation execution failed: " + e)));
            throw e;
        }
    }

    private void save(Path root, ThroughputSearch search, ThroughputResult.Repetition entry) throws Exception {
        Path destination = root.resolve("throughput.json");
        var repetitions = Files.exists(destination)
                ? new ArrayList<>(mapper.readValue(destination.toFile(), ThroughputResult.class).repetitions())
                : new ArrayList<ThroughputResult.Repetition>();
        repetitions.removeIf(r -> r.repetition() == entry.repetition());
        repetitions.add(entry);
        Path temporary = root.resolve(".throughput.json.tmp");
        mapper.writeValue(temporary.toFile(), new ThroughputResult(search, repetitions));
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private ThroughputStage.Result stage(ExecutionEnvironment environment, PreparedExperiment experiment, Path root,
                                         String relative, ThroughputStage plan, PhaseTracker.PhaseUpdater progress) throws Exception {
        String template = Files.readString(root.resolve("hyperfoil.yaml"));
        plan = planForTemplate(template, plan);
        Path directory = Files.createDirectories(root.resolve(relative));
        Files.createDirectories(directory.resolve(".nix"));
        Files.createSymbolicLink(directory.resolve(".nix/experiment"), root.resolve(".nix/experiment").toRealPath());
        if (Files.exists(root.resolve("hyperfoil-data"))) {
            Files.createSymbolicLink(directory.resolve("hyperfoil-data"), root.resolve("hyperfoil-data").toRealPath());
        }
        ObjectNode metadata = (ObjectNode) mapper.readTree(root.resolve("metadata.json").toFile());
        ((ObjectNode) metadata.path("load").path("protocol")).set("ops",
                mapper.valueToTree(plan.phases().stream().map(ThroughputStage.Phase::rate).toList()));
        metadata.put("stage", plan.stage());
        mapper.writeValue(directory.resolve("metadata.json").toFile(), metadata);
        mapper.writeValue(directory.resolve("stage-plan.json").toFile(), plan);
        Files.writeString(directory.resolve("hyperfoil.yaml"), workload(template, experiment.search(), plan));
        var record = new LinkedHashMap<String, Object>();
        record.put("id", relative);
        record.put("parent", root.toAbsolutePath().toString());
        record.put("state", "RUNNING");
        record.put("started", Instant.now().toString());
        mapper.writeValue(directory.resolve("run.json").toFile(), record);
        var tracker = new PhaseTracker(mapper, directory);
        try {
            // The environment call includes stop, collection and bootstrap reset, even on failure.
            try (var ignored = tracker.start()) {
                environment.execute(experiment, directory, (phase, percent, display) -> {
                    tracker.updater(relative).update(phase, percent, display);
                    progress.update(phase, percent, relative + (display == null ? "" : ": " + display));
                });
            }
            if (Files.exists(directory.resolve("environment.json")) && !Files.exists(root.resolve("environment.json"))) {
                Files.copy(directory.resolve("environment.json"), root.resolve("environment.json"));
            }
            var result = plan.evaluate(mapper.readValue(directory.resolve("output.json").toFile(), BenchmarkStats.class),
                    mapper.readValue(directory.resolve("stage-completion.json").toFile(), ThroughputStage.Completion.class));
            mapper.writeValue(directory.resolve("stage-result.json").toFile(), result);
            record.put("state", "SUCCEEDED");
            return result;
        } catch (Exception e) {
            record.put("state", e instanceof InterruptedException ? "CANCELLED" : "FAILED");
            record.put("failure", e.toString());
            mapper.writeValue(directory.resolve("stage-result.json").toFile(), plan.invalid("Stage execution failed: " + e));
            throw e;
        } finally {
            record.put("finished", Instant.now().toString());
            mapper.writeValue(directory.resolve("run.json").toFile(), record);
        }
    }

    @SuppressWarnings("unchecked")
    static ThroughputStage planForTemplate(String template, ThroughputStage plan) {
        Map<String, Object> definition = new Yaml(new SafeConstructor(new LoaderOptions())).load(template);
        var phases = (List<Map<String, Object>>) definition.get("phases");
        int requests = 0;
        if (phases.getFirst().containsKey("preflight")) {
            var preflight = (Map<String, Object>) phases.getFirst().get("preflight");
            var settings = (Map<String, Object>) preflight.get("atOnce");
            requests = ((Number) settings.get("users")).intValue();
            if (requests <= 0) throw new IllegalArgumentException("Preflight requires positive request count");
        }
        return new ThroughputStage(plan.stage(), plan.warmupMillis(), plan.phases(), plan.rampMillis(), requests);
    }

    @SuppressWarnings("unchecked")
    static String workload(String template, ThroughputSearch search, ThroughputStage plan) {
        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        var loader = new LoaderOptions();
        loader.setAllowDuplicateKeys(false);
        var yaml = new Yaml(new SafeConstructor(loader), new Representer(options), options, loader);
        Map<String, Object> definition = yaml.load(template);
        var phases = (List<Map<String, Object>>) definition.get("phases");
        int warmupIndex = phases.getFirst().containsKey("preflight") ? 1 : 0;
        if (phases.size() != warmupIndex + 2 || !phases.get(warmupIndex).containsKey("warmup")
                || !phases.get(warmupIndex + 1).containsKey("main/0")) {
            throw new IllegalArgumentException("Adaptive workload requires optional preflight, warmup and main/0 template phases");
        }
        var main = (Map<String, Object>) ((Map<String, Object>) phases.get(warmupIndex + 1).get("main/0")).get("constantRate");
        String mainTemplate = yaml.dump(main);
        var generated = new ArrayList<Map<String, Object>>();
        if (warmupIndex > 0) generated.add(phases.getFirst());
        var warmup = (Map<String, Object>) ((Map<String, Object>) phases.get(warmupIndex).get("warmup")).values().iterator().next();
        warmup.put("maxDuration", Math.addExact(plan.warmupMillis(), DRAIN_TIMEOUT_MILLIS) + "ms");
        generated.add(phases.get(warmupIndex));
        var execution = plan.executionPhases();
        for (int i = 0; i < execution.size(); i++) {
            var phase = execution.get(i);
            // Give each phase its own nested collections. Sharing them emits YAML aliases that
            // exceed downstream readers' default limit on large discovery/validation sweeps.
            Map<String, Object> settings = yaml.load(mainTemplate);
            settings.put("duration", phase.durationMillis() + "ms");
            settings.put("maxDuration", Math.addExact(phase.durationMillis(), DRAIN_TIMEOUT_MILLIS) + "ms");
            boolean ramp = phase.name().startsWith("ramp/");
            if (ramp) {
                settings.remove("usersPerSec");
                settings.put("initialUsersPerSec", execution.get(i - 1).rate());
                settings.put("targetUsersPerSec", phase.rate());
            } else {
                settings.put("usersPerSec", phase.rate());
            }
            settings.put("maxSessions", (int) Math.ceil(phase.rate() * search.sessionLimitFactor()));
            settings.put("sessionLimitPolicy", "FAIL");
            settings.put("startAfterStrict", i == 0 ? "warmup" : execution.get(i - 1).name());
            generated.add(Map.of(phase.name(), Map.of(ramp ? "increasingRate" : "constantRate", settings)));
        }
        definition.put("phases", generated);
        return yaml.dump(definition);
    }
}
