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
        Path collapsed = Files.createTempFile(directory, "profile-perf-", ".txt");
        try {
            try (OutputStream output = Files.newOutputStream(perfScript)) {
                Nix.run(perfScriptCommand(raw), output, System.err);
            }
            try (BufferedReader input = Files.newBufferedReader(perfScript);
                 BufferedWriter writer = Files.newBufferedWriter(collapsed)) {
                PerfStackCollapse.convert(input, writer);
            }
            Path flamegraph = convert(directory, "flamegraph.html", output -> FlameGraph.convert(collapsed.toString(), output.toString(), new Arguments("--output", "html")));
            return new ProfileArtifacts(profiling, raw, flamegraph, null, null);
        } finally {
            Files.deleteIfExists(perfScript);
            Files.deleteIfExists(collapsed);
        }
    }

    static List<String> perfScriptCommand(Path raw) {
        String profile = raw.toAbsolutePath().normalize().toString();
        return List.of(
                "shell",
                ".#profiling-perf",
                "--command",
                "perf",
                "script",
                "-i",
                profile
        );
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
