package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.api.BatchRequest;
import io.micronaut.benchmark.api.ExperimentRequest;
import io.micronaut.benchmark.api.Nix;
import io.micronaut.benchmark.api.RunRecord;
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
