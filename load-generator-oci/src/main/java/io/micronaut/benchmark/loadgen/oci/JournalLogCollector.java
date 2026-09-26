package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.api.Nix;
import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.core.util.functional.ThrowingSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Follows the persistent journal over the instance's existing SSH-over-HTTPS connection. */
final class JournalLogCollector implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(JournalLogCollector.class);
    private final ThrowingSupplier<CommandRunner, Exception> connect;
    private final OutputListener output;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final Thread thread;
    private volatile boolean closed;
    private volatile CommandRunner connection;
    private volatile String cursor;

    JournalLogCollector(ThrowingSupplier<CommandRunner, Exception> connect, OutputListener output) {
        this.connect = connect;
        this.output = output;
        thread = Thread.ofVirtual().name("benchmark-server-journal").start(this::follow);
    }

    void awaitReady(Duration timeout) throws Exception {
        ready.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void follow() {
        try {
            while (!closed) {
                Records records = new Records(mapper, output, cursor, next -> {
                    cursor = next;
                    ready.complete(null);
                });
                try (CommandRunner client = connect.get()) {
                    connection = client;
                    if (closed) break;
                    // Replay the current boot on initial connection. On reconnect, include and verify
                    // the last delivered entry: --after-cursor alone can silently skip a vacuumed cursor.
                    String command = "journalctl --no-pager --all --output=json --follow --no-tail "
                            + (cursor == null ? "--boot=0" : "--cursor=" + Nix.shellQuote(cursor));
                    try (var builder = client.builder(command)) {
                        builder.setOut(records);
                        builder.setErr(new OutputListener.Stream(List.of(new OutputListener.Log(LOG, Level.WARN))));
                        try (var process = builder.start()) {
                            while (!closed) {
                                if (records.failure != null) throw records.failure;
                                try {
                                    var result = process.waitFor(1, TimeUnit.SECONDS);
                                    if (records.failure != null) throw records.failure;
                                    result.check();
                                    throw new IOException("Journal stream ended unexpectedly");
                                } catch (TimeoutException ignored) {
                                    // Periodically check shutdown and output-processing failures.
                                }
                            }
                        }
                    }
                } catch (CaptureException e) {
                    ready.completeExceptionally(e);
                    LOG.error("Journal capture cannot continue without losing records", e);
                    return;
                } catch (Exception e) {
                    if (!closed) {
                        LOG.warn("Journal stream disconnected; reconnecting from the last delivered cursor", e);
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                            if (closed) break;
                        }
                    }
                } finally {
                    connection = null;
                }
            }
        } finally {
            ready.completeExceptionally(new IOException("Journal capture stopped before becoming ready"));
            output.onComplete();
        }
    }

    @Override
    public void close() throws Exception {
        closed = true;
        try {
            CommandRunner current = connection;
            if (current != null) current.close();
        } finally {
            thread.interrupt();
            if (!thread.join(Duration.ofSeconds(30))) {
                throw new IOException("Journal capture did not stop");
            }
        }
    }

    static final class CaptureException extends IOException {
        CaptureException(String message) {
            super(message);
        }

        CaptureException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** One parser per SSH command; an incomplete last record is replayed after reconnect. */
    static final class Records extends OutputStream {
        private static final int MAX_RECORD_BYTES = 32 * 1024 * 1024;
        private final JsonMapper mapper;
        private final OutputListener output;
        private final String resumeCursor;
        private final Consumer<String> delivered;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private boolean first = true;
        volatile CaptureException failure;

        Records(JsonMapper mapper, OutputListener output, String resumeCursor, Consumer<String> delivered) {
            this.mapper = mapper;
            this.output = output;
            this.resumeCursor = resumeCursor;
            this.delivered = delivered;
        }

        @Override
        public synchronized void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            if (failure != null) throw failure;
            try {
                int start = offset;
                for (int i = offset; i < offset + length; i++) {
                    if (bytes[i] == '\n') {
                        append(bytes, start, i - start);
                        accept();
                        line.reset();
                        start = i + 1;
                    }
                }
                append(bytes, start, offset + length - start);
            } catch (Exception e) {
                failure = e instanceof CaptureException capture ? capture
                        : new CaptureException("Could not save journal record", e);
                throw failure;
            }
        }

        private void append(byte[] bytes, int offset, int length) throws CaptureException {
            if (length > MAX_RECORD_BYTES - line.size()) {
                throw new CaptureException("Journal record exceeds " + MAX_RECORD_BYTES + " bytes");
            }
            line.write(bytes, offset, length);
        }

        private void accept() throws CaptureException {
            if (line.size() == 0) return;
            JsonNode entry = mapper.readTree(line.toByteArray());
            String next = entry.path("__CURSOR").asText();
            if (next.isEmpty()) throw new CaptureException("Journal record has no cursor");
            if (first && resumeCursor != null) {
                first = false;
                if (!resumeCursor.equals(next)) {
                    throw new CaptureException("Journal resume cursor is no longer available; refusing to skip records");
                }
                // The inclusive cursor record was already delivered on the preceding connection.
                delivered.accept(next);
                return;
            }
            first = false;
            long micros = Long.parseLong(entry.path("__REALTIME_TIMESTAMP").asText());
            String timestamp = Instant.ofEpochSecond(micros / 1_000_000, micros % 1_000_000 * 1000).toString();
            String identifier = entry.path("SYSLOG_IDENTIFIER").asText();
            if (identifier.isEmpty()) identifier = entry.path("_COMM").asText();
            String pid = entry.path("_PID").asText();
            JsonNode message = entry.path("MESSAGE");
            // journalctl represents binary or repeated fields as JSON arrays; retain those verbatim.
            String text = message.isString() ? message.stringValue() : message.toString();
            String formatted = timestamp + " " + identifier + (pid.isEmpty() ? "" : "[" + pid + "]") + ": " + text + "\n";
            output.onData(ByteBuffer.wrap(formatted.getBytes(StandardCharsets.UTF_8)));
            // Commit only complete records after the destination has accepted all their bytes.
            delivered.accept(next);
        }
    }
}
