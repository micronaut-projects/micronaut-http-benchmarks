package io.micronaut.benchmark.cli;

import io.micronaut.benchmark.api.Nix;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProfileQueryTest {
    @TempDir
    Path directory;

    @Name("bench.QueryFixture")
    static class Sample extends Event {
        long weight;
    }

    private static void emit(int depth, long weight) {
        if (depth > 0) emit(depth - 1, weight);
        else {
            Sample event = new Sample();
            event.weight = weight;
            event.commit();
        }
    }

    @Test
    void importsRealRecordingWithDeepStacksAndMeasurementBoundsAndInvalidatesCache() throws Exception {
        Path recording = directory.resolve("profile.jfr");
        try (Recording r = new Recording()) {
            r.enable(Sample.class).withStackTrace();
            r.start();
            emit(20, 10);
            Thread.sleep(10);
            emit(20, 20);
            Thread.sleep(10);
            emit(20, 30);
            r.stop();
            r.dump(recording);
        }
        var events = RecordingFile.readAllEvents(recording).stream()
                .filter(e -> e.getEventType().getName().equals("bench.QueryFixture"))
                .sorted(java.util.Comparator.comparing(e -> e.getStartTime())).toList();
        assertEquals(3, events.size());
        long start = events.get(1).getStartTime().toEpochMilli();
        long end = events.get(2).getStartTime().toEpochMilli();
        Files.writeString(directory.resolve("metadata.json"), """
                {"profiling":{"tool":"async-profiler","artifact":"profile.jfr"},"profileCoverage":"process-lifetime"}
                """);
        Files.writeString(directory.resolve("run.json"), """
                {"state":"SUCCEEDED","id":"test's-run"}
                """);
        writeOutput(start, end, 1);
        Path tool = ProfileQuery.buildTool(new Nix(Bench.JSON), "../nix", Map.of());
        ProfileQuery profile = new ProfileQuery(tool);
        Path database = profile.prepare(directory, 256);
        assertEquals("n,bytes\n1,20\n", query(profile, database,
                "SELECT count(*) AS n, sum(weight) AS bytes FROM \"bench.QueryFixture\" WHERE benchmark_measured(startTime)"));
        assertEquals("n\n3\n", query(profile, database, "SELECT count(*) AS n FROM \"bench.QueryFixture\""));
        assertEquals("deep\ntrue\n", query(profile, database,
                "SELECT min(len(list_filter(\"stackTrace$methods\", m -> m != 0))) > 10 AS deep FROM \"bench.QueryFixture\""));
        assertTrue(query(profile, database, "SELECT run_id FROM benchmark_run").contains("test's-run"));
        var context = new ByteArrayOutputStream();
        profile.context(database, context);
        assertTrue(context.toString().contains("benchmark_phases"));
        assertTrue(context.toString().contains("benchmark_measured"));

        var timestamp = Files.getLastModifiedTime(database);
        assertEquals(database, profile.prepare(directory, 256));
        assertEquals(timestamp, Files.getLastModifiedTime(database), "Unchanged inputs must reuse the database");
        assertThrows(IOException.class, () -> query(profile, database, "SELECT * FROM missing_table"));

        writeOutput(start, end, 2);
        profile.prepare(directory, 256);
        assertEquals("response_count\n2\n", query(profile, database, "SELECT response_count FROM benchmark_phases"));
        assertEquals("n,bytes\n1,20\n", query(profile, database,
                "SELECT count(*) AS n, sum(weight) AS bytes FROM \"bench.QueryFixture\" WHERE benchmark_measured(startTime)"));

        profile.prepare(directory, 1);
        assertEquals("depth\n1\n", query(profile, database,
                "SELECT min(len(list_filter(\"stackTrace$methods\", m -> m != 0))) AS depth FROM \"bench.QueryFixture\""));
        profile.prepare(directory, 256);

        byte[] original = Files.readAllBytes(recording);
        byte[] manifest = Files.readAllBytes(database.resolveSibling("manifest.json"));
        Files.writeString(recording, "invalid recording");
        assertThrows(Exception.class, () -> profile.prepare(directory, 256));
        assertArrayEquals(manifest, Files.readAllBytes(database.resolveSibling("manifest.json")));
        assertEquals("n\n3\n", query(profile, database, "SELECT count(*) AS n FROM \"bench.QueryFixture\""));
        Files.write(recording, original);
        profile.prepare(directory, 256);
        assertEquals("n\n3\n", query(profile, database, "SELECT count(*) AS n FROM \"bench.QueryFixture\""));
    }

    private void writeOutput(long start, long end, long responses) throws IOException {
        Bench.JSON.writeValue(directory.resolve("output.json").toFile(), Map.of("failures", List.of(), "stats", List.of(
                Map.of("name", "main/0", "total", Map.of("summary", Map.of(
                        "startTime", start, "endTime", end, "requestCount", responses, "responseCount", responses))))));
    }

    private static String query(ProfileQuery profile, Path database, String sql) throws Exception {
        var output = new ByteArrayOutputStream();
        profile.query(database, sql, true, output);
        return output.toString().replace("\r\n", "\n");
    }
}
