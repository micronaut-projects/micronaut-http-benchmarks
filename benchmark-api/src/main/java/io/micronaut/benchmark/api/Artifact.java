package io.micronaut.benchmark.api;

import java.nio.file.Path;

public record Artifact(String remote, String path, boolean directory) {
    public Artifact {
        Path target = Path.of(path);
        if (path.isBlank() || target.isAbsolute() || !target.equals(target.normalize()) || path.contains("\\")
                || target.startsWith("..") || target.getName(0).toString().startsWith(".")) {
            throw new IllegalArgumentException("Invalid artifact destination: " + path);
        }
        Path source = Path.of(remote);
        if (!source.isAbsolute() || !source.equals(source.normalize()) || !source.startsWith("/var/lib/sut")
                || source.equals(Path.of("/var/lib/sut"))) {
            throw new IllegalArgumentException("Artifact must be beneath /var/lib/sut: " + remote);
        }
    }
}
