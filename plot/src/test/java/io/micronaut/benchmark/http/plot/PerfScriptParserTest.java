package io.micronaut.benchmark.http.plot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PerfScriptParserTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesNumericHeaderSuffixWithoutRestrictingCommandNames() throws Exception {
        String input = """
                   worker pool 12 123/456 [007] 42.000000001: cycles:u:
                    7f leaf;symbol+0x1 (lib.so)
                    80 root (app)
                """;
        List<PerfScriptParser.Sample> samples = new ArrayList<>();

        PerfScriptParser.parse(new BufferedReader(new StringReader(input)), samples::add);

        assertEquals(1, samples.size());
        PerfScriptParser.Sample sample = samples.getFirst();
        assertEquals("worker pool 12", sample.command());
        assertEquals(456, sample.tid());
        assertEquals(7, sample.cpu());
        assertEquals(42_000_000_001L, sample.timestampNanos());
        assertEquals("cycles:u", sample.event());
        assertEquals(List.of("leaf:symbol+0x1", "root"), sample.frames());
    }

    @Test
    void parsesDefaultHeaderWithPeriodAndNoPrintedCpu() throws Exception {
        List<PerfScriptParser.Sample> samples = new ArrayList<>();
        PerfScriptParser.parse(new BufferedReader(new StringReader("""
                python worker 4062 3497.040185: 10101010 cpu-clock:
                 7f leaf (lib.so)
                """)), samples::add);

        assertEquals(1, samples.size());
        assertEquals("python worker", samples.getFirst().command());
        assertEquals(4062, samples.getFirst().tid());
        assertEquals(-1, samples.getFirst().cpu());
        assertEquals("cpu-clock", samples.getFirst().event());
    }

    @Test
    void rejectsSubNanosecondTimestampsExactly() {
        assertThrows(IOException.class, () -> PerfScriptParser.parse(
                new BufferedReader(new StringReader("cmd 1 [000] 1.0000000001: cycles:\n 7f frame (app)\n")),
                sample -> {
                }));
    }

    @Test
    void summaryRejectsAnyLocalTimestampDecrease() throws Exception {
        Path input = temporaryDirectory.resolve("decreasing.txt");
        Files.writeString(input, """
                worker 1 [000] 10.0: cycles:
                 7f first (app)

                worker 1 [000] 12.0: cycles:
                 7f second (app)

                worker 1 [000] 11.0: cycles:
                 7f third (app)
                """);

        assertThrows(IOException.class, () -> PerfScriptParser.summarize(input));
    }

    @Test
    void summaryReportsValidatedBoundsAndInclusiveDuration() throws Exception {
        Path input = temporaryDirectory.resolve("ordered.txt");
        Files.writeString(input, """
                worker 1 [000] 10.000000001: cycles:
                 7f first (app)

                worker 1 [000] 10.000000011: cycles:
                 7f second (app)
                """);

        PerfScriptParser.Summary summary = PerfScriptParser.summarize(input);

        assertEquals(2, summary.count());
        assertEquals(10_000_000_001L, summary.firstTimestampNanos());
        assertEquals(10_000_000_011L, summary.lastTimestampNanos());
        assertEquals(11, summary.durationNanos());
    }

    @Test
    void summaryRejectsZeroSamples() throws Exception {
        Path input = temporaryDirectory.resolve("empty.txt");
        Files.createFile(input);

        assertThrows(IOException.class, () -> PerfScriptParser.summarize(input));
    }
}
