package io.micronaut.benchmark.http.plot;

import io.micronaut.benchmark.loadgen.oci.FrameworkRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProfileConverterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void missingOrEmptyDeclaredSupplementalArtifactsAreRejected() throws Exception {
        FrameworkRun.Profiling profiling = new FrameworkRun.Profiling(
                "perf", "profile.data", "profile.jit.data", "profile-symbols");
        IOException missingInjected = assertThrows(IOException.class, () ->
                ProfileConverter.perfScriptCommand(temporaryDirectory, profiling, temporaryDirectory.resolve("profile.data")));
        assertTrue(missingInjected.getMessage().contains("profile.jit.data"));

        Files.createFile(temporaryDirectory.resolve("profile.jit.data"));
        Files.createDirectory(temporaryDirectory.resolve("profile-symbols"));
        IOException emptyInjected = assertThrows(IOException.class, () ->
                ProfileConverter.perfScriptCommand(temporaryDirectory, profiling, temporaryDirectory.resolve("profile.data")));
        assertTrue(emptyInjected.getMessage().contains("non-empty file"));

        Files.writeString(temporaryDirectory.resolve("profile.jit.data"), "profile");
        IOException emptySymbols = assertThrows(IOException.class, () ->
                ProfileConverter.perfScriptCommand(temporaryDirectory, profiling, temporaryDirectory.resolve("profile.data")));
        assertTrue(emptySymbols.getMessage().contains("non-empty directory"));
    }

    @Test
    void perfScriptConversionProducesBothViewsAndCleansIntermediates() throws Exception {
        Path raw = temporaryDirectory.resolve("profile.data");
        Path perfScript = temporaryDirectory.resolve("input.txt");
        Files.writeString(raw, "raw identity");
        Files.writeString(perfScript, """
                command with spaces 123 [001] 1.0: cycles:
                 7f leaf (lib.so)
                 80 root (app)
                """);
        FrameworkRun.Profiling profiling = new FrameworkRun.Profiling("perf", "profile.data");

        ProfileConverter.ProfileArtifacts artifacts =
                ProfileConverter.convertPerfScript(temporaryDirectory, profiling, raw, perfScript);

        assertEquals(raw, artifacts.raw());
        assertEquals(temporaryDirectory.resolve("flamegraph.html"), artifacts.flamegraph());
        assertEquals(temporaryDirectory.resolve("heatmap.html"), artifacts.heatmap());
        assertTrue(Files.size(artifacts.flamegraph()) > 0);
        String heatmap = Files.readString(artifacts.heatmap());
        assertTrue(heatmap.contains("<html"));
        assertTrue(heatmap.contains("leaf"));
        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().matches("profile-perf-.*\\.(txt|jfr)")));
        }
    }

    @Test
    void malformedPerfScriptStillCleansIntermediates() throws Exception {
        Path raw = temporaryDirectory.resolve("profile.data");
        Path perfScript = temporaryDirectory.resolve("input.txt");
        Files.writeString(raw, "raw identity");
        Files.writeString(perfScript, " 7f orphan (lib.so)\n");

        IOException failure = assertThrows(IOException.class, () -> ProfileConverter.convertPerfScript(
                temporaryDirectory, new FrameworkRun.Profiling("perf", "profile.data"), raw, perfScript));

        assertTrue(failure.getMessage().contains("line 1"));
        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().matches("profile-perf-.*\\.(txt|jfr)")));
        }
    }

    @Test
    void lateTimestampValidationFailureRemovesPublishedOutputs() throws Exception {
        Path raw = temporaryDirectory.resolve("profile.data");
        Path perfScript = temporaryDirectory.resolve("input.txt");
        Files.writeString(raw, "raw identity");
        Files.writeString(perfScript, """
                worker 1 [000] 10.0: cycles:
                 7f first (app)

                worker 1 [000] 12.0: cycles:
                 7f second (app)

                worker 1 [000] 11.0: cycles:
                 7f third (app)
                """);

        IOException failure = assertThrows(IOException.class, () -> ProfileConverter.convertPerfScript(
                temporaryDirectory, new FrameworkRun.Profiling("perf", "profile.data"), raw, perfScript));

        assertTrue(failure.getMessage().contains("sample 3"));
        assertTrue(Files.notExists(temporaryDirectory.resolve("flamegraph.html")));
        assertTrue(Files.notExists(temporaryDirectory.resolve("heatmap.html")));
        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().matches("profile-perf-.*\\.(txt|jfr)")));
        }
    }
}
