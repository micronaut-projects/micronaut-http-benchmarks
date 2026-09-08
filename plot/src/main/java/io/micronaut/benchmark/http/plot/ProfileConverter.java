package io.micronaut.benchmark.http.plot;

import io.micronaut.benchmark.loadgen.oci.FrameworkRun;
import io.micronaut.benchmark.loadgen.oci.Nix;
import one.convert.Arguments;
import one.convert.FlameGraph;
import one.convert.JfrToFlame;
import one.convert.JfrToHeatmap;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

final class ProfileConverter {
    private ProfileConverter() {
    }

    static ProfileArtifacts convert(Path directory, FrameworkRun.Profiling profiling) throws IOException, InterruptedException {
        Path raw = directory.resolve(profiling.artifact());
        if (!Files.isRegularFile(raw) || Files.size(raw) == 0) {
            return ProfileArtifacts.absent(profiling);
        }
        deleteGenerated(directory);
        return switch (profiling.tool()) {
            case "async-profiler" -> convertJfr(directory, profiling, raw);
            case "perf" -> convertPerf(directory, profiling, raw);
            case "py-spy" -> convertCollapsed(directory, profiling, raw);
            default -> throw new IllegalArgumentException("Unsupported profiling tool: " + profiling.tool());
        };
    }

    private static void deleteGenerated(Path directory) throws IOException {
        for (String name : new String[]{"flamegraph.html", "flamegraph-reverse.html", "heatmap.html"}) {
            Files.deleteIfExists(directory.resolve(name));
        }
    }

    private static ProfileArtifacts convertJfr(Path directory, FrameworkRun.Profiling profiling, Path raw) throws IOException {
        Path flamegraph = convert(directory, "flamegraph.html", output -> JfrToFlame.convert(raw.toString(), output.toString(), new Arguments("--output", "html")));
        Path reverse = convert(directory, "flamegraph-reverse.html", output -> JfrToFlame.convert(raw.toString(), output.toString(), new Arguments("-r", "--output", "html")));
        Path heatmap = convert(directory, "heatmap.html", output -> JfrToHeatmap.convert(raw.toString(), output.toString(), new Arguments("--output", "heatmap")));
        return new ProfileArtifacts(profiling, raw, flamegraph, reverse, heatmap);
    }

    private static ProfileArtifacts convertPerf(Path directory, FrameworkRun.Profiling profiling, Path raw) throws IOException, InterruptedException {
        Path perfScript = Files.createTempFile(directory, "profile-perf-script-", ".txt");
        try {
            try (OutputStream output = Files.newOutputStream(perfScript)) {
                Nix.run(perfScriptCommand(directory, profiling, raw), output, System.err);
            }
            return convertPerfScript(directory, profiling, raw, perfScript);
        } finally {
            Files.deleteIfExists(perfScript);
        }
    }

    static ProfileArtifacts convertPerfScript(
            Path directory, FrameworkRun.Profiling profiling, Path raw, Path perfScript) throws IOException {
        try {
            Path collapsed = Files.createTempFile(directory, "profile-perf-", ".txt");
            Path flamegraph;
            try {
                try (BufferedReader input = Files.newBufferedReader(perfScript);
                     BufferedWriter writer = Files.newBufferedWriter(collapsed)) {
                    PerfStackCollapse.convert(input, writer);
                }
                flamegraph = convert(directory, "flamegraph.html", output ->
                        FlameGraph.convert(collapsed.toString(), output.toString(), new Arguments("--output", "html")));
            } finally {
                Files.deleteIfExists(collapsed);
            }

            Path syntheticJfr = Files.createTempFile(directory, "profile-perf-", ".jfr");
            try {
                PerfJfrWriter.convert(perfScript, syntheticJfr);
                Path heatmap = convert(directory, "heatmap.html", output -> convertHeatmap(syntheticJfr, output));
                return new ProfileArtifacts(profiling, raw, flamegraph, null, heatmap);
            } finally {
                Files.deleteIfExists(syntheticJfr);
            }
        } catch (IOException | RuntimeException failure) {
            deleteAfterFailedPerfConversion(directory, failure);
            throw failure;
        }
    }

    private static void deleteAfterFailedPerfConversion(Path directory, Exception failure) {
        for (String name : new String[]{"flamegraph.html", "heatmap.html"}) {
            try {
                Files.deleteIfExists(directory.resolve(name));
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    static void convertHeatmap(Path input, Path output) throws IOException {
        JfrToHeatmap.convert(input.toString(), output.toString(), new Arguments("--output", "heatmap"));
        try (BufferedReader reader = Files.newBufferedReader(output)) {
            if ("No samples found".equals(reader.readLine())) {
                throw new IOException("Heatmap conversion found no samples");
            }
        }
    }

    static List<String> perfScriptCommand(Path directory, FrameworkRun.Profiling profiling, Path raw) throws IOException {
        String injectedArtifact = profiling.injectedArtifact();
        String symbolDirectory = profiling.symbolDirectory();
        if (injectedArtifact == null && symbolDirectory == null) {
            return List.of(
                    "shell", ".#profiling-perf", "--command", "perf", "script", "--ns", "-i",
                    raw.toAbsolutePath().normalize().toString());
        }
        if (injectedArtifact == null || symbolDirectory == null) {
            throw new IOException("Perf supplemental metadata must declare both injectedArtifact and symbolDirectory");
        }

        Path injected = directory.resolve(injectedArtifact).toAbsolutePath().normalize();
        Path symbols = directory.resolve(symbolDirectory).toAbsolutePath().normalize();
        if (!Files.isRegularFile(injected) || Files.size(injected) == 0) {
            throw new IOException("Declared perf injected artifact must be a non-empty file: " + injected);
        }
        if (!isNonEmptyDirectory(symbols)) {
            throw new IOException("Declared perf symbol directory must be a non-empty directory: " + symbols);
        }
        return List.of(
                "shell", ".#profiling-perf", "--command", "perf", "script", "--ns",
                "--symfs", symbols.toString(), "-i", injected.toString());
    }

    private static boolean isNonEmptyDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var entries = Files.list(directory)) {
            return entries.findAny().isPresent();
        }
    }

    private static ProfileArtifacts convertCollapsed(Path directory, FrameworkRun.Profiling profiling, Path raw) throws IOException {
        Path merged = Files.createTempFile(directory, "profile-py-spy-merged-", ".txt");
        try {
            try (BufferedReader input = Files.newBufferedReader(raw);
                 BufferedWriter output = Files.newBufferedWriter(merged)) {
                PySpyStackMerge.convert(input, output);
            }
            Path flamegraph = convert(directory, "flamegraph.html", output -> FlameGraph.convert(merged.toString(), output.toString(), new Arguments("--output", "html")));
            return new ProfileArtifacts(profiling, raw, flamegraph, null, null);
        } finally {
            Files.deleteIfExists(merged);
        }
    }

    private static Path convert(Path directory, String fileName, Conversion conversion) throws IOException {
        Path output = directory.resolve(fileName);
        Path temporary = Files.createTempFile(directory, fileName, ".tmp");
        try {
            conversion.convert(temporary);
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (Files.size(output) == 0) {
                Files.delete(output);
                throw new IOException("Profile conversion produced an empty file: " + output);
            }
            return output;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private interface Conversion {
        void convert(Path output) throws IOException;
    }

    record ProfileArtifacts(FrameworkRun.Profiling profiling, Path raw, Path flamegraph, Path reverseFlamegraph, Path heatmap) {
        static ProfileArtifacts absent(FrameworkRun.Profiling profiling) {
            return new ProfileArtifacts(profiling, null, null, null, null);
        }

        static ProfileArtifacts failed(FrameworkRun.Profiling profiling, Path raw) {
            return new ProfileArtifacts(profiling, raw, null, null, null);
        }

        boolean available() {
            return raw != null;
        }
    }
}
