package io.micronaut.benchmark.http.plot;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;
import one.convert.Arguments;
import one.convert.JfrToHeatmap;
import one.jfr.JfrReader;
import one.jfr.event.ExecutionSample;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

            other worker 200 [003] 10.123456790: cycles:
             81 second-leaf (lib.so)
             80 root (app)""";

    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesTimingThreadsAndLeafFirstStacksForJdkAndAsyncProfilerReaders() throws Exception {
        Path recording = writeRecording();

        List<RecordedEvent> events = RecordingFile.readAllEvents(recording);
        assertEquals(2, events.size());
        assertEquals(Duration.ofNanos(123_456_789),
                Duration.between(events.get(0).getStartTime(), events.get(1).getStartTime()));
        assertEquals("worker pool", events.get(0).getThread("sampledThread").getJavaName());
        assertEquals(101, events.get(0).getThread("sampledThread").getOSThreadId());
        assertEquals(List.of("leaf", "root"), events.get(0).getStackTrace().getFrames().stream()
                .map(frame -> symbolText(frame.getMethod().getValue("name"))).toList());
        assertEquals("Native", events.get(0).getStackTrace().getFrames().getFirst().getType());
        assertEquals("STATE_DEFAULT", events.get(0).getValue("state"));

        try (JfrReader reader = new JfrReader(recording.toString())) {
            assertEquals(123_456_790L, reader.chunkDurationNanos());
            List<ExecutionSample> samples = reader.readAllEvents(ExecutionSample.class);
            assertEquals(2, samples.size());
            assertNotEquals(0, samples.get(0).stackTraceId);
            assertNotEquals(0, samples.get(0).tid);
            assertEquals(2, reader.stackTraces.get(samples.get(0).stackTraceId).methods.length);
            assertEquals(3, reader.stackTraces.get(samples.get(0).stackTraceId).types[0]);
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

    private static String symbolText(RecordedObject symbol) {
        Object[] values = symbol.getValue("bytes");
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
