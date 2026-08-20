package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import jakarta.inject.Singleton;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Singleton
public class Nix {
    private static final List<String> NIX_LOCAL = List.of(
            "/nix/var/nix/profiles/default/bin/nix",
            "--extra-experimental-features", "nix-command",
            "--extra-experimental-features", "flakes"
    );

    private final JsonMapper jsonMapper;

    public Nix(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    private void nix(OutputListener log, List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>(NIX_LOCAL);
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(Path.of("nix").toFile());
        pb.command(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (OutputStream stdout = new OutputListener.Stream(List.of(log))) {
            process.getInputStream().transferTo(stdout);
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Nix exit with next output: " + exit);
        }
    }

    private JsonNode nixJson(OutputListener log, List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>(NIX_LOCAL);
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(Path.of("nix").toFile());
        pb.command(cmd);
        Process process = pb.start();
        AtomicReference<IOException> stderrFailure = new AtomicReference<>();
        Thread stderrThread = Thread.ofVirtual().start(() -> {
            try {
                process.getErrorStream().transferTo(new OutputListener.Stream(List.of(log)));
            } catch (IOException e) {
                stderrFailure.set(e);
            }
        });
        byte[] bytes = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        stderrThread.join();
        if (stderrFailure.get() != null) {
            throw stderrFailure.get();
        }
        if (exit != 0) {
            throw new IllegalStateException("Nix exit with next output: " + exit);
        }
        return jsonMapper.readTree(bytes);
    }

    public Path build(OutputListener log, String installable) throws Exception {
        return build(log, installable, List.of());
    }

    public Path resolveOutput(OutputListener log, String installable) throws Exception {
        JsonNode answer = nixJson(log, outputResolutionArguments(installable));
        return outputPath(answer.get(0).get("outputs").get("out").stringValue());
    }

    static List<String> outputResolutionArguments(String installable) {
        return List.of("build", installable, "--dry-run", "--json", "--no-link");
    }

    public Path build(OutputListener log, String installable, List<String> extraArgs) throws Exception {
        List<String> args = new ArrayList<>();
        args.add("build");
        args.add(installable);
        args.addAll(extraArgs);
        args.addAll(List.of("--json", "--no-link"));
        JsonNode answer = nixJson(log, args);
        return outputPath(answer.get(0).get("outputs").get("out").stringValue());
    }

    public byte[] buildBenchmarkMetadata(OutputListener log) throws Exception {
        return Files.readAllBytes(build(log, ".#benchmark-metadata"));
    }

    public static String activate(URI cacheUri, String outputPath) {
        return "/run/current-system/sw/bin/benchmark-nix-activate "
                + shellQuote(cacheUri.toString()) + " "
                + shellQuote(outputPath(outputPath).toString()) + "\n";
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public Path buildAndUploadOutputCache(OutputListener log, URI cache, String installable) throws Exception {
        Path output = build(log, installable);
        uploadOutputCache(log, cache, output);
        return output;
    }

    public void uploadOutputCache(OutputListener log, URI cache, Path output) throws Exception {
        nix(log, List.of("copy", "--to", cache + "?compression=zstd", outputPath(output.toString()).toString()));
    }

    public Path addStorePath(OutputListener log, Path localDirectory) throws Exception {
        if (!Files.isDirectory(localDirectory)) {
            throw new IllegalArgumentException("PGO path is not a directory: " + localDirectory);
        }
        return nixStoreAdd(log, List.of("store", "add", localDirectory.toAbsolutePath().normalize().toString()));
    }

    public Path buildPgoOutput(OutputListener log, String optimizedConfiguration, Path pgoStorePath) throws Exception {
        Path validatedPgoStorePath = outputPath(pgoStorePath.toString());
        JsonNode value = nixJson(log, List.of("eval", "--json", "--impure", "--expr",
                "(let flake = builtins.getFlake \"path:${toString ../.}?dir=nix\"; in flake.lib.pgoToplevel "
                        + nixString(optimizedConfiguration) + " (builtins.storePath " + nixString(validatedPgoStorePath.toString()) + ")).drvPath"));
        Path derivation = derivationPath(value.stringValue());
        return build(log, derivation + "^out");
    }

    private Path nixStoreAdd(OutputListener log, List<String> args) throws Exception {
        List<String> command = new ArrayList<>(NIX_LOCAL);
        command.addAll(args);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(Path.of("nix").toFile());
        Process process = processBuilder.start();
        AtomicReference<IOException> stderrFailure = new AtomicReference<>();
        Thread stderrThread = Thread.ofVirtual().start(() -> {
            try (OutputStream stderr = new OutputListener.Stream(List.of(log))) {
                process.getErrorStream().transferTo(stderr);
            } catch (IOException e) {
                stderrFailure.set(e);
            }
        });
        byte[] output = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        stderrThread.join();
        if (stderrFailure.get() != null) {
            throw stderrFailure.get();
        }
        if (exit != 0) {
            throw new IllegalStateException("Nix exit with next output: " + exit);
        }
        String storePath = new String(output, java.nio.charset.StandardCharsets.UTF_8).strip();
        if (storePath.lines().count() != 1) {
            throw new IllegalStateException("Malformed Nix store path: " + storePath);
        }
        return outputPath(storePath);
    }

    private static Path outputPath(String value) {
        return storePath(value, false);
    }

    private static Path derivationPath(String value) {
        return storePath(value, true);
    }

    private static Path storePath(String value, boolean derivation) {
        Path path = Path.of(value);
        Path store = Path.of("/nix/store");
        if (!path.startsWith(store) || path.getNameCount() != store.getNameCount() + 1 || derivation != path.getFileName().toString().endsWith(".drv")) {
            throw new IllegalStateException("Malformed Nix store path: " + value);
        }
        return path;
    }

    private static String nixString(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("${", "\\${") + '"';
    }
}
