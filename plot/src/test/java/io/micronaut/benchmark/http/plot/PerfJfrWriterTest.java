package io.micronaut.benchmark.http.plot;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import one.convert.Arguments;
import one.convert.JfrToHeatmap;
import one.jfr.JfrReader;
import one.jfr.event.ExecutionSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PerfJfrWriterTest {
    private static final String PERF_SCRIPT = """
            worker pool 100/101 [002] 10.000000001: cycles:
             7f leaf (lib.so)
             80 root (app)

            GC Thread#0 100/102 [003] 10.123456790: cycles:
             ffffffff81000100 tcp_sendmsg ([kernel.kallsyms])
             ffffffff81000200 __sys_sendto ([kernel.kallsyms])
             81 second-leaf (lib.so)
             80 root (app)

            VM Thread 100/103 [000] 10.223456790: cycles:
             ffffffff81000300 schedule ([kernel.kallsyms])
             ffffffff81000400 worker_thread ([kernel.kallsyms])""";

    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesTimingThreadsAndLeafFirstStacksForJdkAndAsyncProfilerReaders() throws Exception {
        List<List<String>> expectedStacks = List.of(
                List.of("leaf", "root"),
                List.of("tcp_sendmsg", "__sys_sendto", "second-leaf", "root"),
                List.of("schedule", "worker_thread"));
        Path recording = writeRecording();

        List<RecordedEvent> events = RecordingFile.readAllEvents(recording);
        assertEquals(3, events.size());
        assertEquals(Duration.ofNanos(123_456_789),
                Duration.between(events.get(0).getStartTime(), events.get(1).getStartTime()));
        assertEquals(List.of("worker pool", "GC Thread#0", "VM Thread"), events.stream()
                .map(event -> event.getThread("sampledThread").getJavaName()).toList());
        assertEquals(List.of(101L, 102L, 103L), events.stream()
                .map(event -> event.getThread("sampledThread").getOSThreadId()).toList());
        assertEquals(expectedStacks, events.stream()
                .map(event -> event.getStackTrace().getFrames().stream()
                        .map(frame -> frame.getMethod().getName()).toList()).toList());
        assertEquals("C++", events.get(0).getStackTrace().getFrames().getFirst().getType());
        assertEquals("Kernel", events.get(1).getStackTrace().getFrames().getFirst().getType());
        assertTrue(events.stream().flatMap(event -> event.getStackTrace().getFrames().stream())
                .noneMatch(frame -> frame.isJavaFrame()));
        assertEquals("STATE_DEFAULT", events.get(0).getValue("state"));

        try (JfrReader reader = new JfrReader(recording.toString())) {
            assertEquals(223_456_790L, reader.chunkDurationNanos());
            List<ExecutionSample> samples = reader.readAllEvents(ExecutionSample.class);
            assertEquals(3, samples.size());
            assertNotEquals(0, samples.get(0).stackTraceId);
            assertNotEquals(0, samples.get(0).tid);
            assertEquals(3L, samples.stream().map(sample -> sample.tid).distinct().count());
            assertEquals(expectedStacks, samples.stream()
                    .map(sample -> Arrays.stream(reader.stackTraces.get(sample.stackTraceId).methods)
                            .mapToObj(method -> new String(reader.symbols.get(reader.methods.get(method).name),
                                    StandardCharsets.UTF_8)).toList()).toList());
            assertEquals(2, reader.stackTraces.get(samples.get(0).stackTraceId).methods.length);
            assertEquals(4, reader.stackTraces.get(samples.get(0).stackTraceId).types[0]);
            assertEquals(5, reader.stackTraces.get(samples.get(1).stackTraceId).types[0]);
        }
    }

    @Test
    void generatesARealHeatmapRatherThanTheNoSamplesFallback() throws Exception {
        Path recording = writeRecording();
        Path heatmap = temporaryDirectory.resolve("heatmap.html");

        JfrToHeatmap.convert(recording.toString(), heatmap.toString(), new Arguments("--output", "heatmap"));

        String html = Files.readString(heatmap);
        assertFalse(html.isBlank());
        assertFalse(html.contains("No samples found"));
        assertTrue(html.contains("<html"));
        assertTrue(html.contains("leaf"));
        assertTrue(html.contains("root"));
        assertTrue(html.contains("tcp_sendmsg"));
        assertTrue(html.contains("__sys_sendto"));
        assertTrue(html.contains("second-leaf"));
        assertTrue(html.contains("schedule"));
        assertTrue(html.contains("worker_thread"));
    }

    @Test
    void rejectsEmptyInputBeforeCreatingARecording() throws Exception {
        Path input = temporaryDirectory.resolve("empty.txt");
        Path recording = temporaryDirectory.resolve("empty.jfr");
        Files.createFile(input);

        IOException failure = assertThrows(IOException.class, () -> PerfJfrWriter.convert(input, recording));

        assertTrue(failure.getMessage().contains("no samples"));
        assertTrue(Files.notExists(recording));
    }

    @Test
    void rejectsAsyncProfilerNoSamplesFallback() throws Exception {
        Path recording = temporaryDirectory.resolve("no-events.jfr");
        try (var ignored = Recordings.newRecording(recording)) {
        }
        Path heatmap = temporaryDirectory.resolve("fallback.html");

        IOException failure = assertThrows(IOException.class,
                () -> ProfileConverter.convertHeatmap(recording, heatmap));

        assertTrue(failure.getMessage().contains("no samples"));
    }

    private Path writeRecording() throws Exception {
        Path input = temporaryDirectory.resolve("perf-script.txt");
        Files.writeString(input, PERF_SCRIPT);
        Path recording = temporaryDirectory.resolve("synthetic.jfr");
        PerfJfrWriter.convert(input, recording);
        assertTrue(Files.size(recording) > 0);
        return recording;
    }
}
