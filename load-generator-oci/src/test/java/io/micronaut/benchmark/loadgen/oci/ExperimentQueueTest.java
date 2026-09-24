package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.api.BatchRequest;
import io.micronaut.benchmark.api.ExperimentRequest;
import io.micronaut.benchmark.api.Nix;
import io.micronaut.benchmark.api.RunRecord;
import io.micronaut.benchmark.api.ThroughputSearch;
import io.micronaut.benchmark.api.ThroughputStage;
import io.micronaut.benchmark.api.ThroughputResult;
import io.micronaut.http.exceptions.HttpStatusException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real Nix builds, with a local environment at the OCI boundary.
 */
class ExperimentQueueTest {
    @TempDir
    Path temporary;
    static String derivation;
    static String namedDerivation;
    static String adaptiveDerivation;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Nix NIX = new Nix(JSON);

    @BeforeAll
    static void experiment() throws Exception {
        String flake = Path.of("../nix").toAbsolutePath().normalize().toString();
        String expression = """
                let pkgs = import (builtins.getFlake %s).inputs.nixpkgs { system = builtins.currentSystem; };
                in (pkgs.linkFarm "queue-test-experiment" [
                  { name = "system"; path = pkgs.emptyDirectory; }
                  { name = "metadata.json"; path = pkgs.writeText "metadata" ''{"opaque":"untouched"}''; }
                  { name = "requirements.json"; path = pkgs.writeText "requirements" ''{"version":1,"instanceType":{"shape":"test","ocpus":1,"memoryInGb":1,"platform":"x86_64-linux","diskPerformanceUnits":10},"kernel":"test","attachments":[]}''; }
                  { name = "artifacts.json"; path = pkgs.writeText "artifacts" ''[{"remote":"/var/lib/sut/profile.dat","path":"profile.dat","directory":false}]''; }
                  { name = "hyperfoil.yaml"; path = pkgs.writeText "workload" "name: test"; }
                ]).drvPath
                """.formatted(NIX.expressionString(flake));
        derivation = NIX.capture(List.of("eval", "--impure", "--raw", "--expr", expression), System.err);
        String named = "let pkgs = import (builtins.getFlake " + NIX.expressionString(flake)
                + ").inputs.nixpkgs { system = builtins.currentSystem; }; in (pkgs.runCommand \"queue-test-named-output\""
                + " { outputs = [ \"out\" \"bundle\" ]; source = import " + NIX.expressionString(derivation)
                + "; } \"mkdir $out; cp -r $source $bundle\").drvPath";
        namedDerivation = NIX.capture(List.of("eval", "--impure", "--raw", "--expr", named), System.err);
        String search = JSON.writeValueAsString(new ThroughputSearch("thorough", 100, 125, "1s", "1s", "1s", 25, 5, 2, 2));
        String workload = """
                name: benchmark
                agents: {}
                phases:
                - warmup: { always: { duration: 1s, users: 1 } }
                - main/0: { constantRate: { usersPerSec: 100, duration: 1s } }
                """;
        String adaptive = """
                let pkgs = import (builtins.getFlake %s).inputs.nixpkgs { system = builtins.currentSystem; };
                in (pkgs.runCommand "adaptive-queue-test" { source = import %s; } ''
                  mkdir $out
                  cp -r $source/. $out/
                  rm $out/metadata.json $out/hyperfoil.yaml
                  cp ${pkgs.writeText "search" %s} $out/search.json
                  cp ${pkgs.writeText "workload" %s} $out/hyperfoil.yaml
                  cp ${pkgs.writeText "metadata" ''{"load":{"protocol":{"ops":[100]}}}''} $out/metadata.json
                '').drvPath
                """.formatted(NIX.expressionString(flake), NIX.expressionString(derivation),
                NIX.expressionString(search), NIX.expressionString(workload));
        adaptiveDerivation = NIX.capture(List.of("eval", "--impure", "--raw", "--expr", adaptive), System.err);
    }

    ExperimentRequest request() {
        return new ExperimentRequest(derivation, "out", temporary.resolve("runs").toString(), Map.of("sut", "same-name"));
    }

    ExperimentQueue queue(LocalEnvironment environment) throws Exception {
        return new ExperimentQueue(JSON, NIX, temporary.resolve("daemon"), Duration.ofHours(2),
                () -> environment, () -> {
        });
    }

    @Test
    void adaptivePairsResetAndRetainArtifactsAndRunInRepetitionRounds() throws Exception {
        var calls = new CopyOnWriteArrayList<Path>();
        var environment = new LocalEnvironment(temporary.resolve("remote")) {
            @Override
            public void execute(PreparedExperiment experiment, Path directory, PhaseTracker.PhaseUpdater progress) throws Exception {
                assertFalse(Files.exists(remote), "Previous stage must have reset before starting this stage");
                calls.add(directory);
                super.execute(experiment, directory, progress);
                writePassingStage(directory);
            }
        };
        try (var queue = queue(environment)) {
            var request = new ExperimentRequest(adaptiveDerivation, "out", temporary.resolve("runs").toString(), Map.of());
            var batch = queue.submit(new BatchRequest(List.of(request, request)));
            await(() -> environment.entered.getCount() == 0 || queue.run(batch.runs().getFirst().id()).terminal());
            assertEquals(0, environment.entered.getCount(), queue.run(batch.runs().getFirst().id()).toString());
            environment.release.countDown();
            await(() -> queue.batch(batch.id()).finished());
            assertNull(queue.batch(batch.id()).failure());
            assertEquals(8, calls.size());
            for (int i = 0; i < calls.size(); i++) {
                Path stage = calls.get(i);
                assertEquals(i % 2 == 0 ? "discovery" : "validation", stage.getFileName().toString());
                assertEquals(i < 4 ? "1" : "2", stage.getParent().getFileName().toString());
                assertTrue(Files.exists(stage.resolve("profile.dat")));
                assertTrue(Files.exists(stage.resolve("stage-result.json")));
                assertEquals("SUCCEEDED", JSON.readTree(stage.resolve("run.json").toFile()).path("state").asString());
                var definition = Files.readString(stage.resolve("hyperfoil.yaml"));
                assertTrue(definition.contains("warmup"));
                assertTrue(definition.contains("duration: 1s"));
            }
            assertNotEquals(calls.get(0).getParent().getParent().getParent(), calls.get(2).getParent().getParent().getParent());
            for (var run : batch.runs()) {
                var result = JSON.readValue(Path.of(run.directory()).resolve("throughput.json").toFile(), ThroughputResult.class);
                assertEquals(2, result.repetitions().size());
                assertTrue(result.repetitions().stream().allMatch(r -> "LOWER_BOUND".equals(r.validation().outcome())));
            }
        }
    }

    @Test
    void cancellingDiscoveryDoesNotStartValidationOrAnotherRepetition() throws Exception {
        var environment = new LocalEnvironment(temporary.resolve("remote"));
        try (var queue = queue(environment)) {
            var request = new ExperimentRequest(adaptiveDerivation, "out", temporary.resolve("runs").toString(), Map.of());
            var batch = queue.submit(new BatchRequest(List.of(request)));
            await(() -> environment.entered.getCount() == 0 || queue.run(batch.runs().getFirst().id()).terminal());
            assertEquals(0, environment.entered.getCount(), queue.run(batch.runs().getFirst().id()).toString());
            var run = batch.runs().getFirst();
            queue.cancel(run.id());
            await(() -> queue.batch(batch.id()).finished());
            assertEquals("CANCELLED", queue.run(run.id()).state());
            Path repetition = Path.of(run.directory()).resolve("repetitions/1");
            assertTrue(Files.exists(repetition.resolve("discovery/profile.dat")));
            assertFalse(Files.exists(repetition.resolve("validation")));
            assertFalse(Files.exists(Path.of(run.directory()).resolve("repetitions/2")));
            var result = JSON.readValue(Path.of(run.directory()).resolve("throughput.json").toFile(), ThroughputResult.class);
            assertEquals("INVALID", result.repetitions().getFirst().discovery().outcome());
            assertNull(result.aggregate());
            assertFalse(Files.exists(environment.remote));
        }
    }

    static void writePassingStage(Path directory) throws Exception {
        var plan = JSON.readValue(directory.resolve("stage-plan.json").toFile(), ThroughputStage.class);
        var stats = new ArrayList<Map<String, Object>>();
        var terminated = new ArrayList<String>();
        var phases = new ArrayList<>(plan.phases());
        phases.addFirst(new ThroughputStage.Phase("warmup", 100, plan.warmupMillis()));
        for (var phase : phases) {
            var summary = new java.util.LinkedHashMap<String, Object>();
            for (String key : List.of("minResponseTime", "maxResponseTime", "meanResponseTime", "stdDevResponseTime",
                    "invalid", "connectionErrors", "requestTimeouts", "internalErrors", "blockedTime")) summary.put(key, 0);
            summary.put("startTime", 1000);
            summary.put("endTime", 1000 + phase.durationMillis());
            summary.put("requestCount", 100);
            summary.put("responseCount", 100);
            summary.put("percentileResponseTime", Map.of());
            summary.put("extensions", Map.of("http", Map.of("@type", "http", "status_2xx", 100)));
            stats.add(Map.of("name", phase.name(), "phase", phase.name(), "total", Map.of("summary", summary)));
            terminated.add(phase.name());
        }
        JSON.writeValue(directory.resolve("output.json").toFile(), Map.of("info", Map.of("errors", List.of()), "failures", List.of(), "stats", stats));
        JSON.writeValue(directory.resolve("stage-completion.json").toFile(), new ThroughputStage.Completion(true, false, terminated));
    }

    @Test
    void concurrentInvocationsCancelWithoutContaminationAndRetainIndependentResults() throws Exception {
        LocalEnvironment environment = new LocalEnvironment(temporary.resolve("remote"));
        try (ExperimentQueue queue = queue(environment); var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ExperimentQueue.BatchView>> submissions = new ArrayList<>();
            for (int i = 0; i < 4; i++)
                submissions.add(clients.submit(() -> queue.submit(new BatchRequest(List.of(request())))));
            List<RunRecord> runs = new ArrayList<>();
            for (var submitted : submissions) runs.add(submitted.get().runs().getFirst());
            assertEquals(4, runs.stream().map(RunRecord::directory).distinct().count());
            assertTrue(environment.entered.await(60, TimeUnit.SECONDS));
            String cancelled = environment.executing;
            queue.cancel(cancelled);
            for (var run : runs) await(() -> queue.run(run.id()).terminal());
            assertEquals("CANCELLED", queue.run(cancelled).state());
            assertEquals(3, runs.stream().filter(r -> queue.run(r.id()).state().equals("SUCCEEDED")).count());
            for (var run : runs) {
                Path directory = Path.of(run.directory());
                assertEquals(run.id(), Files.readString(directory.resolve("profile.dat")));
                assertEquals("{\"opaque\":\"untouched\"}", Files.readString(directory.resolve("metadata.json")));
                assertTrue(Files.exists(directory.resolve(".nix/experiment/system")));
                assertTrue(environment.events.indexOf("reset:" + run.id()) < environment.events.indexOf("end:" + run.id()));
            }
            Path removed = Path.of(runs.getFirst().directory());
            try (var paths = Files.walk(removed)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            for (var run : runs.subList(1, runs.size())) {
                assertEquals(run.id(), Files.readString(Path.of(run.directory()).resolve("profile.dat")));
            }
        }
    }

    @Test
    void namedOutputUsesTheSameRetainedExperimentPath() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("named"));
        Path root = directory.resolve("experiment");
        Path realized = NIX.realize(Path.of(namedDerivation), "bundle", root, System.err);
        assertEquals(realized, root.toRealPath());
        assertTrue(Files.exists(root.resolve("metadata.json")));
    }

    @Test
    void exclusiveBatchKeepsTheSameInfrastructureForPrecedingAndFollowingJobs() throws Exception {
        LocalEnvironment environment = new LocalEnvironment(temporary.resolve("remote"));
        try (ExperimentQueue queue = queue(environment)) {
            var previous = queue.submit(new BatchRequest(List.of(request())));
            assertTrue(environment.entered.await(60, TimeUnit.SECONDS));
            var batch = queue.submit(new BatchRequest(List.of(request(), request())));
            var next = queue.submit(new BatchRequest(List.of(request())));
            environment.release.countDown();
            await(() -> queue.batch(next.id()).finished());
            assertTrue(queue.batch(batch.id()).finished());
            assertNull(queue.batch(batch.id()).failure());
            List<RunRecord> ordered = List.of(previous.runs().getFirst(), batch.runs().get(0),
                    batch.runs().get(1), next.runs().getFirst());
            List<String> expected = new ArrayList<>(List.of("up"));
            for (var run : ordered) {
                assertEquals("SUCCEEDED", queue.run(run.id()).state());
                assertEquals(environment.identity, queue.run(run.id()).environmentId());
                expected.addAll(List.of("start:" + run.id(), "reset:" + run.id(), "end:" + run.id()));
            }
            assertEquals(expected, environment.events);
        }
        assertEquals("down", environment.events.getLast());
        assertEquals(1, environment.events.stream().filter("down"::equals).count());
    }

    @Test
    void infrastructureStartupFailureStopsTheDaemonWithoutTryingQueuedJobs() throws Exception {
        var stopped = new CountDownLatch(1);
        var environment = new LocalEnvironment(temporary.resolve("remote")) {
            @Override
            public void up(PhaseTracker.PhaseUpdater progress) throws Exception {
                super.up(progress);
                entered.countDown();
                release.await();
                throw new EnvironmentInvalidException("Startup failed", new IOException("Provisioning failed"));
            }
        };
        String pending;
        ExperimentQueue queue = new ExperimentQueue(JSON, NIX, temporary.resolve("daemon"), Duration.ofHours(2),
                () -> environment, stopped::countDown);
        try (queue) {
            var first = queue.submit(new BatchRequest(List.of(request())));
            assertTrue(environment.entered.await(60, TimeUnit.SECONDS));
            pending = queue.submit(new BatchRequest(List.of(request()))).runs().getFirst().id();
            environment.release.countDown();
            assertTrue(stopped.await(60, TimeUnit.SECONDS));
            assertEquals("FAILED", queue.run(first.runs().getFirst().id()).state());
            assertEquals(List.of("up", "down"), environment.events);
            assertThrows(HttpStatusException.class,
                    () -> queue.submit(new BatchRequest(List.of(request()))));
        }
        assertEquals("CANCELLED", queue.run(pending).state());
    }

    @Test
    void unusedDaemonExpiresWithoutProvisioningAndReleasesItsLock() throws Exception {
        var stopped = new CountDownLatch(1);
        Path state = temporary.resolve("daemon");
        try (var queue = new ExperimentQueue(JSON, NIX, state, Duration.ofMillis(100),
                () -> {
                    throw new AssertionError("Idle daemon must not provision infrastructure");
                }, stopped::countDown)) {
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
            assertEquals(true, queue.environmentStatus().get("stopping"));
            assertThrows(HttpStatusException.class,
                    () -> queue.submit(new BatchRequest(List.of(request()))));
        }
        try (var replacement = new ExperimentQueue(JSON, NIX, state, Duration.ofHours(2),
                () -> {
                    throw new AssertionError();
                }, () -> {
        })) {
            assertEquals(false, replacement.environmentStatus().get("stopping"));
        }
    }

    @Test
    void idleDeadlineStartsAfterQueuedMeasurementsAndShutdownWaitsForCleanup() throws Exception {
        var stopped = new CountDownLatch(1);
        var cleaning = new CountDownLatch(1);
        var finishCleanup = new CountDownLatch(1);
        var environment = new LocalEnvironment(temporary.resolve("remote")) {
            @Override
            public void down() throws Exception {
                cleaning.countDown();
                assertTrue(finishCleanup.await(10, TimeUnit.SECONDS));
                super.down();
            }
        };
        try (var queue = new ExperimentQueue(JSON, NIX, temporary.resolve("daemon"), Duration.ofMillis(300),
                () -> environment, stopped::countDown)) {
            try {
                queue.submit(new BatchRequest(List.of(request())));
                assertTrue(environment.entered.await(60, TimeUnit.SECONDS));
                var next = queue.submit(new BatchRequest(List.of(request())));
                assertFalse(cleaning.await(900, TimeUnit.MILLISECONDS), "Active and queued work must not expire");
                environment.release.countDown();
                await(() -> queue.batch(next.id()).finished());
                assertEquals("SUCCEEDED", queue.run(next.runs().getFirst().id()).state());
                assertTrue(cleaning.await(5, TimeUnit.SECONDS));
                assertFalse(stopped.await(100, TimeUnit.MILLISECONDS), "Cleanup must finish before process shutdown");
                assertThrows(HttpStatusException.class,
                        () -> queue.submit(new BatchRequest(List.of(request()))));
            } finally {
                environment.release.countDown();
                finishCleanup.countDown();
            }
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cleanupFailureStillStopsTheDaemon() throws Exception {
        var stopped = new CountDownLatch(1);
        var environment = new LocalEnvironment(temporary.resolve("remote")) {
            @Override
            public void down() throws Exception {
                throw new IOException("Teardown failed");
            }
        };
        environment.release.countDown();
        try (var queue = new ExperimentQueue(JSON, NIX, temporary.resolve("daemon"), Duration.ofMillis(300),
                () -> environment, stopped::countDown)) {
            var batch = queue.submit(new BatchRequest(List.of(request())));
            assertTrue(stopped.await(60, TimeUnit.SECONDS));
            assertEquals("SUCCEEDED", queue.run(batch.runs().getFirst().id()).state());
            assertThrows(HttpStatusException.class,
                    () -> queue.submit(new BatchRequest(List.of(request()))));
        }
    }

    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Queue did not complete");
            }
            Thread.sleep(20);
        }
    }

    static class LocalEnvironment implements ExecutionEnvironment {
        final Path remote;
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile String executing;
        String identity;
        boolean first = true;

        LocalEnvironment(Path remote) {
            this.remote = remote;
        }

        public String id() {
            return identity;
        }

        public void validate(PreparedExperiment experiment) {
        }

        public void up(PhaseTracker.PhaseUpdater progress) throws Exception {
            if (identity == null) {
                identity = UUID.randomUUID().toString();
                events.add("up");
            }
        }

        public void down() throws Exception {
            identity = null;
            events.add("down");
        }

        public void execute(PreparedExperiment experiment, Path directory, PhaseTracker.PhaseUpdater progress) throws Exception {
            String id = directory.getFileName().toString();
            events.add("start:" + id);
            Files.deleteIfExists(remote);
            Files.writeString(remote, id);
            executing = id;
            try {
                progress.update(BenchmarkPhase.BENCHMARKING);
                if (first) {
                    first = false;
                    entered.countDown();
                    release.await();
                }
            } finally {
                Files.copy(remote, directory.resolve("profile.dat"));
                Files.delete(remote);
                events.add("reset:" + id);
                events.add("end:" + id);
            }
        }
    }
}
