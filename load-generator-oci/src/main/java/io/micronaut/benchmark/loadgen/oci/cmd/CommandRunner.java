package io.micronaut.benchmark.loadgen.oci.cmd;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Stream;

public interface CommandRunner extends Closeable {
    Set<PosixFilePermission> DEFAULT_PERMISSIONS = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    ProcessBuilder builder(String command) throws IOException;

    default ProcessHandle run(String command, OutputListener... log) throws IOException {
        try (ProcessBuilder builder = builder(command)) {
            builder.forwardOutput(log);
            return builder.start();
        }
    }

    default void runAndCheck(String command, OutputListener... log) throws IOException {
        try (ProcessHandle handle = run(command, log)) {
            handle.waitFor().check();
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    void upload(byte[] local, String remote, Set<PosixFilePermission> permissions) throws IOException;

    default void upload(Path local, String remote, Set<PosixFilePermission> permissions) throws IOException {
        upload(Files.readAllBytes(local), remote, permissions);
    }

    default void upload(Path local, String remote) throws IOException {
        upload(local, remote, DEFAULT_PERMISSIONS);
    }

    default void uploadRecursive(Path local, String remote) throws IOException {
        if (Files.isDirectory(local)) {
            runAndCheck("mkdir -p -- " + remote);
            try (Stream<Path> stream = Files.list(local)) {
                Iterator<Path> itr = stream.iterator();
                while (itr.hasNext()) {
                    Path f = itr.next();
                    uploadRecursive(f, remote + "/" + f.getFileName().toString());
                }
            }
        } else {
            upload(local, remote);
        }
    }

    byte[] downloadBytes(String path) throws IOException;

    default void download(String remote, Path local) throws IOException {
        Files.write(local, downloadBytes(remote));
    }

    default void downloadRecursive(String remote, Path local) throws IOException {
        ByteArrayOutputStream listing = new ByteArrayOutputStream();
        runAndCheck("ls --literal --almost-all --indicator-style=none -1 -- " + remote, new OutputListener.Write(listing));
        String[] parts = listing.toString(StandardCharsets.UTF_8).split("\n");
        if (parts.length == 1 && parts[0].equals(remote)) {
            download(remote, local);
        } else {
            try {
                Files.createDirectory(local);
            } catch (FileAlreadyExistsException ignored) {
            }
            if (parts.length > 0 && (parts.length > 1 || !parts[0].isEmpty())) {
                for (String part : parts) {
                    Path l = local.resolve(part).normalize();
                    if (!l.startsWith(local)) {
                        throw new IOException("`ls` returned weird entry: " + part);
                    }
                    downloadRecursive(remote + "/" + part, l);
                }
            }
        }
    }

    PortForwardHandle portForward(InetSocketAddress remoteAddress) throws IOException;
}
