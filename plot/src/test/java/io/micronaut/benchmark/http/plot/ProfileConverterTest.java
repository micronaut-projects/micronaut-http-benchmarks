package io.micronaut.benchmark.http.plot;

import io.micronaut.benchmark.loadgen.oci.FrameworkRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

        Path kallsyms = temporaryDirectory.resolve("profile-symbols/proc/kallsyms");
        Files.createDirectory(kallsyms.getParent());
        IOException missingKallsyms = assertThrows(IOException.class, () ->
                ProfileConverter.perfScriptCommand(temporaryDirectory, profiling, temporaryDirectory.resolve("profile.data")));
        assertTrue(missingKallsyms.getMessage().contains("non-empty file"));
        assertTrue(missingKallsyms.getMessage().contains(kallsyms.toString()));

        Files.createFile(kallsyms);
        IOException emptyKallsyms = assertThrows(IOException.class, () ->
                ProfileConverter.perfScriptCommand(temporaryDirectory, profiling, temporaryDirectory.resolve("profile.data")));
        assertTrue(emptyKallsyms.getMessage().contains("non-empty file"));
        assertTrue(emptyKallsyms.getMessage().contains(kallsyms.toString()));

        Files.delete(kallsyms);
        Files.createDirectory(kallsyms);
        IOException directoryKallsyms = assertThrows(IOException.class, () ->
                ProfileConverter.perfScriptCommand(temporaryDirectory, profiling, temporaryDirectory.resolve("profile.data")));
        assertTrue(directoryKallsyms.getMessage().contains("non-empty file"));
        assertTrue(directoryKallsyms.getMessage().contains(kallsyms.toString()));
    }

    @Test
    void supplementalPerfCommandUsesRecordingHostSymbolsAndInjectedArtifact() throws Exception {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("recording host"));
        Path raw = directory.resolve("profile.data");
        Path injected = directory.resolve("profile.jit.data");
        Path symbols = directory.resolve("profile-symbols");
        Path kallsyms = symbols.resolve("proc/kallsyms");
        Files.writeString(injected, "profile");
        Files.createDirectories(kallsyms.getParent());
        Files.writeString(kallsyms, "ffffffff81000000 T _stext\n");
        FrameworkRun.Profiling profiling = new FrameworkRun.Profiling(
                "perf", "profile.data", "profile.jit.data", "profile-symbols");

        List<String> command = ProfileConverter.perfScriptCommand(directory, profiling, raw);

        assertEquals(List.of(
                "shell", ".#profiling-perf", "--command", "perf", "script", "--ns",
                "--symfs", symbols.toAbsolutePath().normalize().toString(),
                "--kallsyms", kallsyms.toAbsolutePath().normalize().toString(),
                "-i", injected.toAbsolutePath().normalize().toString()), command);
    }

    @Test
    void rawOnlyPerfCommandDoesNotRequireSupplementalArtifacts() throws Exception {
        Path raw = temporaryDirectory.resolve("profile.data");
        FrameworkRun.Profiling profiling = new FrameworkRun.Profiling("perf", "profile.data");

        List<String> command = ProfileConverter.perfScriptCommand(temporaryDirectory, profiling, raw);

        assertEquals(List.of(
                "shell", ".#profiling-perf", "--command", "perf", "script", "--ns",
                "-i", raw.toAbsolutePath().normalize().toString()), command);
    }

    @Test
    void perfScriptConversionProducesBothViewsAndCleansIntermediates() throws Exception {
        Path raw = temporaryDirectory.resolve("profile.data");
        Path perfScript = temporaryDirectory.resolve("input.txt");
        Files.writeString(raw, "raw identity");
        Files.writeString(perfScript, """
                command with spaces 123 [001] 1.0: cycles:
                 7f leaf (lib.so)
                 81 Ljava/lang/String;::charAt [JIT] (jitted-456-1.so)
                 82 io.micronaut.benchmark.Controller::hello [AOT] (benchmark-aot)
                 83 JavaMainWrapper::invoke_main [AOT] (benchmark-aot)
                 84 schedule ([kernel.kallsyms])
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
        assertTrue(heatmap.contains("\"io.micronaut.benchmark.Controller\""));
        assertTrue(heatmap.contains("\"hello\""));
        assertTrue(heatmap.contains("\"JavaMainWrapper\""));
        assertTrue(heatmap.contains("\"invoke_main\""));
        assertTrue(heatmap.contains("\"java.lang.String\""));
        assertTrue(heatmap.contains("\"charAt\""));
        assertTrue(heatmap.contains("schedule"));
        String flamegraph = Files.readString(artifacts.flamegraph());
        assertTrue(flamegraph.contains("io.micronaut.benchmark.Controller::hello [AOT]"));
        assertTrue(flamegraph.contains("JavaMainWrapper::invoke_main [AOT]"));
        assertTrue(flamegraph.contains("Ljava/lang/String:::charAt [JIT]"));
        assertTrue(flamegraph.contains("schedule"));
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
