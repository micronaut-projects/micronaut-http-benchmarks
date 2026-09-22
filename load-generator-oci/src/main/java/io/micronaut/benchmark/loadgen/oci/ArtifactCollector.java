package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.api.Artifact;
import io.micronaut.benchmark.api.Nix;
import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

final class ArtifactCollector {
    private ArtifactCollector() {
    }

    static void clear(CommandRunner client, List<Artifact> artifacts, OutputListener.Write log) throws IOException {
        for (Artifact artifact : artifacts) client.runAndCheck("rm -rf -- " + Nix.shellQuote(artifact.remote()), log);
    }

    static void collect(CommandRunner client, Path directory, List<Artifact> artifacts, OutputListener.Write log) throws IOException {
        IOException failure = null;
        for (Artifact artifact : artifacts) {
            Path destination = directory.resolve(artifact.path());
            Path partial = destination.resolveSibling("." + destination.getFileName() + ".partial");
            try {
                Files.createDirectories(destination.getParent());
                if (artifact.directory()) {
                    client.downloadRecursive(artifact.remote(), partial);
                    try (var files = Files.walk(partial)) {
                        List<Path> entries = files.toList();
                        if (entries.size() < 2 || entries.stream().anyMatch(Files::isSymbolicLink)) {
                            throw new IOException("Empty or symlink-containing artifact directory: " + artifact.path());
                        }
                    }
                } else {
                    client.download(artifact.remote(), partial);
                    if (!Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS) || Files.size(partial) == 0) {
                        throw new IOException("Empty artifact: " + artifact.path());
                    }
                }
                Files.move(partial, destination);
            } catch (IOException e) {
                log.println("Artifact collection failed for " + artifact.path() + ": " + e);
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
