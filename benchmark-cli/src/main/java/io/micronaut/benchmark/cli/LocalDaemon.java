package io.micronaut.benchmark.cli;

import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Singleton;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Connects once per CLI invocation; only explicit HTTP rejections may be retried.
 */
@Singleton
final class LocalDaemon {
    private final HttpClient client, probe;
    private final Path project, directory;
    private boolean connected;

    LocalDaemon(@Client("benchmark-daemon") HttpClient client,
                @Client("benchmark-daemon-probe") HttpClient probe) throws Exception {
        this.client = client;
        this.probe = probe;
        Path location = Path.of(LocalDaemon.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        while (location != null && !Files.isRegularFile(location.resolve("nix/flake.nix")))
            location = location.getParent();
        if (location == null) {
            throw new IOException("Run the CLI from this project's Gradle installation");
        }
        project = location;
        directory = project.resolve("output/daemon");
    }

    JsonNode request(String method, String path, Object body) throws Exception {
        if (!connected) {
            start();
        }
        try {
            return send(client, method, path, body);
        } catch (HttpClientResponseException rejected) {
            // The queue rejects before allocating results once shutdown starts. Never replay
            // a submission after a lost connection: it may already have been accepted.
            if (rejected.getStatus() != HttpStatus.SERVICE_UNAVAILABLE) {
                throw rejected;
            }
            connected = false;
            start();
            return send(client, method, path, body);
        }
    }

    JsonNode stop() throws Exception {
        JsonNode status = status();
        if (status == null) {
            return Bench.JSON.valueToTree(Map.of("stopping", false));
        }
        if (status.path("stopping").asBoolean()) {
            return status;
        }
        return send(client, "POST", "/shutdown", null);
    }

    private void start() throws Exception {
        JsonNode status = status();
        if (ready(status)) {
            connected = true;
            return;
        }
        Files.createDirectories(directory);
        // Separate from daemon.lock: serialize launching clients, including the readiness wait.
        try (var channel = FileChannel.open(directory.resolve("startup.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
            while (true) {
                status = status();
                if (ready(status)) {
                    connected = true;
                    return;
                }
                // Hold no daemon ownership lock while spawning. A previous daemon retains it
                // until collection and OCI cleanup finish, even after its HTTP server stops.
                if (!ownsDaemonLock()) {
                    break;
                }
                if (System.nanoTime() > deadline) {
                    throw new IOException("Timed out waiting for daemon shutdown in " + directory);
                }
                Thread.sleep(150);
            }
            Path command = project.resolve("load-generator-oci/build/install/load-generator-oci/bin/load-generator-oci");
            Path log = project.resolve("output/log");
            Process process = new ProcessBuilder(command.toString()).directory(project.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (true) {
                if (!process.isAlive()) {
                    throw new IOException("Daemon exited during startup; see " + log);
                }
                status = status();
                if (ready(status)) {
                    connected = true;
                    return;
                }
                if (System.nanoTime() > deadline) {
                    throw new IOException("Daemon startup timed out; see " + log);
                }
                Thread.sleep(150);
            }
        }
    }

    private boolean ownsDaemonLock() throws IOException {
        try (var channel = FileChannel.open(directory.resolve("daemon.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            return lock == null;
        }
    }

    private static boolean ready(JsonNode status) {
        return status != null && !status.path("stopping").asBoolean();
    }

    private JsonNode status() throws Exception {
        if (!Files.exists(directory.resolve("token"))) {
            return null;
        }
        try {
            return send(probe, "GET", "/environment", null);
        } catch (HttpClientResponseException response) {
            throw response;
        } catch (HttpClientException unavailable) {
            return null;
        }
    }

    private JsonNode send(HttpClient transport, String method, String path, Object body) throws Exception {
        MutableHttpRequest<String> request = HttpRequest.create(HttpMethod.valueOf(method), "/v1" + path);
        request.header("X-Benchmark-Token", Files.readString(directory.resolve("token")).trim());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON_TYPE).body(Bench.JSON.writeValueAsString(body));
        }
        return Bench.JSON.readTree(transport.toBlocking().retrieve(request, String.class));
    }
}
