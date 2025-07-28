package io.micronaut.benchmark.relay;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

public interface CommandRunner extends Closeable {
    ProcessBuilder builder(String command) throws IOException;

    default ProcessHandle run(String command, OutputListener... log) throws IOException {
        try (ProcessBuilder builder = builder(command)) {
            builder.forwardOutput(log);
            return builder.start();
        }
    }

    void upload(Path local, String remote) throws IOException;

    void uploadRecursive(Path local, String remote) throws IOException;

    void upload(byte[] local, String remote, Set<PosixFilePermission> permissions) throws IOException;

    void download(String remote, Path local) throws IOException;

    void downloadRecursive(String remote, Path local) throws IOException;

    byte[] downloadBytes(String path) throws IOException;
}
