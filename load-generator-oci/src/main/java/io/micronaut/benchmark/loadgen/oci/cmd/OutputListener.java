package io.micronaut.benchmark.loadgen.oci.cmd;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Listener for SSH command output.
 */
public interface OutputListener {
    void onData(ByteBuffer data);

    void onComplete();

    /**
     * This listener waits until a given output is found. This is used to wait for the 'startup complete' log message
     * of different frameworks.
     */
    class Waiter implements OutputListener {
        private final Lock lock = new ReentrantLock();
        private final Condition foundCondition = lock.newCondition();
        private ByteBuffer pattern;
        private boolean done = false;
        private boolean esc = false;
        private boolean csi = false;

        /**
         * @param initialPattern The initial pattern to look for
         */
        public Waiter(ByteBuffer initialPattern) {
            this.pattern = initialPattern;
        }

        boolean found() {
            lock.lock();
            try {
                return pattern == null;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void onData(ByteBuffer byteBuffer) {
            lock.lock();
            try {
                while (byteBuffer.hasRemaining() && pattern != null) {
                    byte actual = byteBuffer.get();
                    // ignore csi sequences (bash color codes)
                    if (csi) {
                        if (actual >= 0x40 && actual <= 0x7e) {
                            csi = false;
                        }
                        continue;
                    } else if (esc) {
                        esc = false;
                        if (actual == '[') {
                            csi = true;
                            continue;
                        }
                    } else if (actual == 0x1b) {
                        esc = true;
                        continue;
                    }

                    byte expected = pattern.get();
                    if (actual != expected) {
                        pattern.position(fallback(pattern, pattern.position() - 1, actual));
                    } else if (!pattern.hasRemaining()) {
                        pattern = null;
                        foundCondition.signalAll();
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * Find the longest prefix of the pattern that is a suffix of the first {@code matched} pattern bytes followed
         * by {@code actual}, i.e. how much of the pattern is still matched after a mismatch.
         */
        private static int fallback(ByteBuffer pattern, int matched, byte actual) {
            for (int candidate = matched; candidate > 0; candidate--) {
                if (pattern.get(candidate - 1) != actual) {
                    continue;
                }
                int shift = matched + 1 - candidate;
                boolean ok = true;
                for (int j = 0; j < candidate - 1; j++) {
                    if (pattern.get(j) != pattern.get(shift + j)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return candidate;
                }
            }
            return 0;
        }

        @Override
        public void onComplete() {
            lock.lock();
            try {
                done = true;
                foundCondition.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /**
         * Wait for the last defined pattern to occur in the output (or the {@code initialPattern} if this method was
         * not called before).
         *
         * @param nextPattern The next pattern to look for, or {@code null} to stop looking
         */
        public void awaitWithNextPattern(ByteBuffer nextPattern) {
            awaitWithNextPattern(nextPattern, null);
        }

        public void awaitWithNextPattern(ByteBuffer nextPattern, @Nullable Duration timeout) {
            boolean interrupt = false;
            lock.lock();
            try {
                long remainingNanos = timeout == null ? 0 : timeout.toNanos();
                while (pattern != null) {
                    if (done) {
                        throw new IllegalStateException("Pattern not found");
                    }
                    if (timeout == null) {
                        foundCondition.awaitUninterruptibly();
                    } else {
                        if (remainingNanos <= 0) {
                            throw new IllegalStateException("Timed out waiting for pattern");
                        }
                        try {
                            remainingNanos = foundCondition.awaitNanos(remainingNanos);
                        } catch (InterruptedException e) {
                            interrupt = true;
                        }
                    }
                }
                pattern = nextPattern;
            } finally {
                lock.unlock();
                if (interrupt) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * A listener that writes all input data to a file.
     */
    class Write implements OutputListener, Closeable {
        private static final Logger LOG = LoggerFactory.getLogger(Write.class);

        private final OutputStream outputStream;
        private final boolean failOnWriteError;

        public Write(OutputStream outputStream) {
            this(outputStream, false);
        }

        public Write(OutputStream outputStream, boolean failOnWriteError) {
            this.outputStream = outputStream;
            this.failOnWriteError = failOnWriteError;
        }

        @Override
        public synchronized void onData(ByteBuffer data) {
            try {
                outputStream.write(data.array(), data.arrayOffset() + data.position(), data.remaining());
            } catch (ClosedChannelException e) {
                if (failOnWriteError) throw new UncheckedIOException(e);
            } catch (IOException e) {
                if (failOnWriteError) throw new UncheckedIOException(e);
                LOG.error("Failed to write data", e);
            }
        }

        public synchronized void println(@NonNull String msg) {
            try {
                outputStream.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (failOnWriteError) throw new UncheckedIOException(e);
                LOG.error("Failed to print message", e);
            }
        }

        @Override
        public void onComplete() {
        }

        @Override
        public synchronized void close() throws IOException {
            outputStream.close();
        }
    }

    class Log implements OutputListener {
        private final Logger logger;
        private final Level level;

        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final Map<String, String> mdc;

        public Log(Logger logger, Level level) {
            this.logger = logger;
            this.level = level;
            this.mdc = MDC.getCopyOfContextMap();
        }

        @Override
        public void onData(ByteBuffer data) {
            for (int i = data.position(); i < data.limit(); i++) {
                if (data.get(i) == '\n') {
                    append(data.slice(data.position(), i - data.position()));
                    log(drain());
                    data.position(i + 1);
                }
            }
            // copy the partial line: the caller may reuse the buffer after we return
            append(data);
        }

        private void append(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            pending.writeBytes(bytes);
        }

        protected void log(String msg) {
            Map<String, String> old = MDC.getCopyOfContextMap();
            try {
                MDC.setContextMap(mdc);
                logger.atLevel(level).log(msg);
            } finally {
                MDC.setContextMap(old);
            }
        }

        @Override
        public void onComplete() {
            log(drain());
        }

        private String drain() {
            String line = pending.toString(StandardCharsets.UTF_8);
            pending.reset();
            return line;
        }
    }

    /**
     * An {@link OutputStream} that forwards the output to a number of {@link OutputListener}s.
     */
    class Stream extends OutputStream {
        private final List<OutputListener> listeners;

        public Stream(List<OutputListener> listeners) {
            this.listeners = listeners;
        }

        @Override
        public void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            for (OutputListener listener : listeners) {
                listener.onData(ByteBuffer.wrap(b, off, len));
            }
        }

        @Override
        public void close() {
            for (OutputListener listener : listeners) {
                listener.onComplete();
            }
        }
    }
}
