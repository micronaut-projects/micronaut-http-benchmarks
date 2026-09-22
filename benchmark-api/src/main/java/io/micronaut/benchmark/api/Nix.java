package io.micronaut.benchmark.api;

import jakarta.inject.Singleton;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Nix process support shared by preparation, execution, and offline profile conversion.
 */
@Singleton
public final class Nix {
    private static final String BIN = "/nix/var/nix/profiles/default/bin/";
    private final JsonMapper jsonMapper;

    public Nix(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public static List<String> command(String executable, List<String> args) {
        var command = new ArrayList<String>();
        command.add(BIN + executable);
        command.addAll(List.of("--extra-experimental-features", "nix-command flakes"));
        command.addAll(args);
        return command;
    }

    public static void run(List<String> args, OutputStream stdout, OutputStream stderr) throws IOException, InterruptedException {
        run(new ProcessBuilder(command("nix", args)), stdout, stderr);
    }

    public static void run(ProcessBuilder builder, OutputStream stdout, OutputStream stderr) throws IOException, InterruptedException {
        Process process = builder.start();
        // Keep the caller interruptible even while a build produces no output.
        try (var streams = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> stdoutTask = streams.submit(() -> {
                forwardOutput(process, process.getInputStream(), stdout);
                return null;
            });
            Future<?> stderrTask = streams.submit(() -> {
                forwardOutput(process, process.getErrorStream(), stderr);
                return null;
            });
            try {
                int exit = process.waitFor();
                stdoutTask.get();
                stderrTask.get();
                if (exit != 0) {
                    throw new IOException("Nix exited with exit code " + exit);
                }
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException io) {
                    throw io;
                }
                throw new IOException("Failed to forward Nix output", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } finally {
                if (process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.onExit().join();
                }
            }
        }
    }

    private static void forwardOutput(Process process, InputStream input, OutputStream output) throws IOException {
        try (input) {
            input.transferTo(output);
        } catch (IOException | RuntimeException | Error failure) {
            // A failed output destination must not leave a build running unobserved.
            process.destroyForcibly();
            throw failure;
        }
    }

    public String capture(List<String> args, OutputStream log) throws IOException, InterruptedException {
        var out = new ByteArrayOutputStream();
        run(args, out, log);
        return out.toString(StandardCharsets.UTF_8).trim();
    }

    public JsonNode json(List<String> args, OutputStream log) throws IOException, InterruptedException {
        return jsonMapper.readTree(capture(args, log));
    }

    public static Path checkStorePath(String value, boolean derivation) {
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.equals(path.normalize()) || !Path.of("/nix/store").equals(path.getParent())
                || path.toString().endsWith(".drv") != derivation) {
            throw new IllegalArgumentException("Expected a canonical Nix " + (derivation ? "derivation" : "output") + " path: " + value);
        }
        return path;
    }

    public String expressionString(String value) {
        return jsonMapper.writeValueAsString(value).replace("${", "\\${");
    }

    public Path realize(Path derivation, String output, Path root, OutputStream log) throws IOException, InterruptedException {
        checkStorePath(derivation.toString(), true);
        if (!output.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid output name");
        }
        Files.createDirectories(root.getParent());
        JsonNode answer = json(List.of("build", derivation + "^" + output, "--json", "--out-link", root.toString()), log);
        Path realized = checkStorePath(answer.get(0).get("outputs").get(output).stringValue(), false);
        if (!output.equals("out")) {
            // nix build appends -<output> to the link name for non-default outputs.
            run(new ProcessBuilder(command("nix-store", List.of("--realise", realized.toString(),
                    "--add-root", root.toString(), "--indirect"))), OutputStream.nullOutputStream(), log);
            Files.deleteIfExists(root.resolveSibling(root.getFileName() + "-" + output));
        }
        return realized;
    }

    public Path resolveOutput(OutputStream log, String installable) throws IOException, InterruptedException {
        if (installable.startsWith("/nix/store/")) {
            return checkStorePath(installable, false);
        }
        JsonNode answer = json(List.of("build", installable, "--dry-run", "--json", "--no-link"), log);
        return checkStorePath(answer.get(0).get("outputs").get("out").stringValue(), false);
    }

    public Path build(OutputStream log, String installable) throws IOException, InterruptedException {
        if (installable.startsWith("/nix/store/")) {
            return checkStorePath(installable, false);
        }
        JsonNode answer = json(List.of("build", installable, "--json", "--no-link"), log);
        return checkStorePath(answer.get(0).get("outputs").get("out").stringValue(), false);
    }

    public Path buildAndUploadOutputCache(OutputStream log, URI cache, String installable) throws IOException, InterruptedException {
        Path output = build(log, installable);
        uploadOutputCache(log, cache, output);
        return output;
    }

    public void uploadOutputCache(OutputStream log, URI cache, Path output) throws IOException, InterruptedException {
        run(new ProcessBuilder(command("nix", List.of("copy", "--to", cache + "?compression=zstd",
                checkStorePath(output.toString(), false).toString()))).redirectErrorStream(true), log, log);
    }

    public static String activate(URI cacheUri, String outputPath) {
        return "/run/current-system/sw/bin/benchmark-nix-activate "
                + shellQuote(cacheUri.toString()) + " " + shellQuote(checkStorePath(outputPath, false).toString()) + "\n";
    }

    public static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
