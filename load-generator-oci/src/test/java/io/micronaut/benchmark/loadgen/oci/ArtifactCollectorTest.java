package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.api.Artifact;
import io.micronaut.benchmark.api.Nix;
import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.cmd.PortForwardHandle;
import io.micronaut.benchmark.loadgen.oci.cmd.ProcessBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactCollectorTest {
    @TempDir
    Path temporary;

    @Test
    void clearsStaleArtifactsAndPreservesAvailableDiagnosticsOnFailure() throws Exception {
        Path remote = Files.createDirectory(temporary.resolve("remote"));
        var client = new LocalFiles(remote);
        var profile = new Artifact("/var/lib/sut/profile.jfr", "profile.jfr", false);
        var symbols = new Artifact("/var/lib/sut/profile-symbols", "profile-symbols", true);
        var artifacts = List.of(profile, symbols);
        try (var log = new OutputListener.Write(OutputStream.nullOutputStream())) {
            Files.writeString(remote.resolve("profile.jfr"), "stale");
            Files.createDirectory(remote.resolve("profile-symbols"));
            Files.writeString(remote.resolve("profile-symbols/stale"), "stale");
            ArtifactCollector.clear(client, artifacts, log);
            assertFalse(Files.exists(remote.resolve("profile.jfr")));
            assertFalse(Files.exists(remote.resolve("profile-symbols")));
            Files.writeString(remote.resolve("profile.jfr"), "current");
            Path failed = Files.createDirectory(temporary.resolve("failed"));
            assertThrows(IOException.class, () -> ArtifactCollector.collect(client, failed, artifacts, log));
            assertEquals("current", Files.readString(failed.resolve("profile.jfr")));
            assertFalse(Files.exists(failed.resolve("profile-symbols")));
            ArtifactCollector.clear(client, artifacts, log);
            Path next = Files.createDirectory(temporary.resolve("next"));
            assertThrows(IOException.class, () -> ArtifactCollector.collect(client, next, artifacts, log));
            assertFalse(Files.exists(next.resolve("profile.jfr")));
            assertEquals("current", Files.readString(failed.resolve("profile.jfr")));
        }
    }

    @Test
    void rejectsEscapingDestinationsAndAllowsDownloadingIntoExistingDirectories() throws Exception {
        for (String path : List.of("../other", "/tmp/other", "a/../../other", ".", ".nix/root")) {
            assertThrows(IllegalArgumentException.class, () -> new Artifact("/var/lib/sut/profile", path, false));
        }
        Path remote = Files.createDirectory(temporary.resolve("remote"));
        var client = new LocalFiles(remote);
        String name = "symbols ' $literal";
        Path tree = Files.createDirectory(remote.resolve(name));
        Files.createDirectory(tree.resolve("nested"));
        Files.writeString(tree.resolve("nested/data"), "symbols");
        Path result = Files.createDirectory(temporary.resolve("result"));
        try (var log = new OutputListener.Write(OutputStream.nullOutputStream())) {
            ArtifactCollector.collect(client, result, List.of(new Artifact("/var/lib/sut/" + name, "symbols", true)), log);
            assertEquals("symbols", Files.readString(result.resolve("symbols/nested/data")));
            Files.writeString(tree.resolve("nested/data"), "updated symbols");
            client.downloadRecursive("/var/lib/sut/" + name, result.resolve("symbols"));
            assertEquals("updated symbols", Files.readString(result.resolve("symbols/nested/data")));
        }
    }

    /**
     * Exercise the production shell/listing/download logic against local files.
     */
    private record LocalFiles(Path remoteRoot) implements CommandRunner {
        public void runAndCheck(String command, OutputListener... listeners) throws IOException {
            try {
                var output = new ByteArrayOutputStream();
                Nix.run(new java.lang.ProcessBuilder("sh", "-c", command.replace("/var/lib/sut", remoteRoot.toString())), output, System.err);
                new OutputListener.Stream(List.of(listeners)).write(output.toString(StandardCharsets.UTF_8)
                        .replace(remoteRoot.toString(), "/var/lib/sut").getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
        }

        public byte[] downloadBytes(String path) throws IOException {
            return Files.readAllBytes(remoteRoot.resolve(Path.of("/var/lib/sut").relativize(Path.of(path))));
        }

        public ProcessBuilder builder(String command) {
            throw new UnsupportedOperationException();
        }

        public void upload(byte[] content, String path, Set<PosixFilePermission> permissions) {
            throw new UnsupportedOperationException();
        }

        public PortForwardHandle portForward(InetSocketAddress address) {
            throw new UnsupportedOperationException();
        }

        public void close() {
        }
    }
}
