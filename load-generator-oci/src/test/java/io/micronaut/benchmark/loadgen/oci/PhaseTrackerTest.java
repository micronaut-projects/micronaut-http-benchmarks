package io.micronaut.benchmark.loadgen.oci;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseTrackerTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    Path output;

    @Test
    void closingAfterFailureFinalizesProgressWithoutMaskingFailure() {
        PhaseTracker tracker = new PhaseTracker(MAPPER, output);
        var progress = tracker.updater("micronaut-http1");
        progress.update(BenchmarkPhase.QUEUED);
        Exception failure = new Exception("benchmark failed");
        assertSame(failure, assertThrows(Exception.class, () -> {
            try (AutoCloseable reporting = tracker.start()) {
                progress.update(BenchmarkPhase.BENCHMARKING);
                progress.update(BenchmarkPhase.FAILED);
                throw failure;
            }
        }));

        assertEquals(List.of(BenchmarkPhase.QUEUED, BenchmarkPhase.BENCHMARKING, BenchmarkPhase.FAILED),
                snapshot().records().stream().map(PhaseTracker.Record::phase).toList());
        assertFalse(Files.exists(output.resolve("phases.new.json")));
        assertFalse(Files.exists(output.resolve("phases.new.json.tmp")));
    }

    @Test
    void interruptedCallerStillWaitsForFinalSnapshot() {
        PhaseTracker tracker = new PhaseTracker(MAPPER, output);
        AutoCloseable reporting = tracker.start();
        tracker.updater("micronaut-http1").update(BenchmarkPhase.DONE);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, reporting::close);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(BenchmarkPhase.DONE, snapshot().records().getLast().phase());
        assertFalse(Files.exists(output.resolve("phases.new.json")));
        assertFalse(Files.exists(output.resolve("phases.new.json.tmp")));
    }

    private PhaseTracker.Dump snapshot() {
        return MAPPER.readValue(output.resolve("phases.json").toFile(), PhaseTracker.Dump.class);
    }
}
