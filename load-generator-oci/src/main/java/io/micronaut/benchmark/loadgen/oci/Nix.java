package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import jakarta.inject.Singleton;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    private static ProcessBuilder processBuilder(List<String> args) {
        List<String> command = new ArrayList<>(NIX_LOCAL.size() + args.size());
        command.addAll(NIX_LOCAL);
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(Path.of("nix").toFile());
        return builder;
    }

    public static void run(List<String> args, OutputStream stdout, OutputStream stderr) throws IOException, InterruptedException {
        run(processBuilder(args), stdout, stderr);
    }

    static void run(ProcessBuilder builder, OutputStream stdout, OutputStream stderr) throws IOException, InterruptedException {
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

    private void nix(OutputListener log, List<String> args) throws Exception {
        try (OutputStream stream = synchronizedOutputStream(new OutputListener.Stream(List.of(log)))) {
            run(args, stream, stream);
        }
    }

    private JsonNode nixJson(OutputListener log, List<String> args) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (OutputStream stderr = new OutputListener.Stream(List.of(log))) {
            run(args, stdout, stderr);
        }
        return jsonMapper.readTree(stdout.toByteArray());
    }

    public Path resolveOutput(OutputListener log, String installable) throws Exception {
        JsonNode answer = nixJson(log, outputResolutionArguments(installable));
        return outputPath(answer.get(0).get("outputs").get("out").stringValue());
    }

    static List<String> outputResolutionArguments(String installable) {
        return List.of("build", installable, "--dry-run", "--json", "--no-link");
    }

    public Path build(OutputListener log, String installable) throws Exception {
        JsonNode answer = nixJson(log, List.of("build", installable, "--json", "--no-link"));
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

    private static OutputStream synchronizedOutputStream(OutputStream delegate) {
        return new OutputStream() {
            @Override
            public synchronized void write(int b) throws IOException {
                delegate.write(b);
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) throws IOException {
                delegate.write(b, off, len);
            }

            @Override
            public synchronized void flush() throws IOException {
                delegate.flush();
            }

            @Override
            public synchronized void close() throws IOException {
                delegate.close();
            }
        };
    }

    private static Path outputPath(String value) {
        Path path = Path.of(value);
        Path store = Path.of("/nix/store");
        if (!path.startsWith(store) || path.getNameCount() != store.getNameCount() + 1 || path.getFileName().toString().endsWith(".drv")) {
            throw new IllegalStateException("Malformed Nix store path: " + value);
        }
        return path;
    }
}
