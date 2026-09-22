package io.micronaut.benchmark.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(10)
class NixProcessTest {
    @Test
    void drainsBothStreamsBeforeReturning() throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Nix.run(new ProcessBuilder("sh", "-c",
                "i=0; while [ \"$i\" -lt 20000 ]; do printf output; printf error >&2; i=$((i + 1)); done"), stdout, stderr);
        assertEquals("output".repeat(20000), stdout.toString());
        assertEquals("error".repeat(20000), stderr.toString());
    }

    @Test
    void propagatesExitFailure() {
        IOException failure = assertThrows(IOException.class, () -> Nix.run(
                new ProcessBuilder("sh", "-c", "exit 7"), OutputStream.nullOutputStream(), OutputStream.nullOutputStream()));
        assertTrue(failure.getMessage().contains("exit code 7"));
    }

    @Test
    void stdoutFailureStopsProcess() {
        outputFailureStopsProcess(false);
    }

    @Test
    void stderrFailureStopsProcess() {
        outputFailureStopsProcess(true);
    }

    private void outputFailureStopsProcess(boolean failStderr) {
        IOException failure = new IOException("write failed");
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw failure;
            }
        };
        assertSame(failure, assertThrows(IOException.class, () -> Nix.run(
                new ProcessBuilder("sh", "-c", "printf output" + (failStderr ? " >&2" : "") + "; exec sleep 60"),
                failStderr ? OutputStream.nullOutputStream() : broken,
                failStderr ? broken : OutputStream.nullOutputStream())));
    }

    @Test
    void interruptionStopsQuietChildProcess(@TempDir Path directory) throws Exception {
        Path pidFile = directory.resolve("pid");
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                Nix.run(new ProcessBuilder("sh", "-c", "echo $$ > \"$1\"; exec sleep 60", "sh", pidFile.toString()),
                        OutputStream.nullOutputStream(), OutputStream.nullOutputStream());
                result.complete(null);
            } catch (Throwable failure) {
                result.complete(failure);
            }
        });
        ProcessHandle child = null;
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            String pid = "";
            while (pid.isBlank()) {
                if (Files.exists(pidFile)) {
                    pid = Files.readString(pidFile).trim();
                }
                if (System.nanoTime() >= deadline) {
                    fail("Child process did not start");
                }
                Thread.sleep(10);
            }
            child = ProcessHandle.of(Long.parseLong(pid)).orElseThrow();
            runner.interrupt();
            assertInstanceOf(InterruptedException.class, result.get(5, TimeUnit.SECONDS));
            assertFalse(child.isAlive());
        } finally {
            runner.interrupt();
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }
}
