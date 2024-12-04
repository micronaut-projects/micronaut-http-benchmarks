package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.core.annotation.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

        /**
         * @param initialPattern The initial pattern to look for
         */
        public Waiter(ByteBuffer initialPattern) {
            this.pattern = initialPattern;
        }

        @Override
        public void onData(ByteBuffer byteBuffer) {
            lock.lock();
            try {
                while (byteBuffer.hasRemaining() && pattern != null) {
                    byte expected = pattern.get();
                    byte actual = byteBuffer.get();
                    if (actual != expected) {
                        pattern.rewind();
                    } else if (!pattern.hasRemaining()) {
                        pattern = null;
                        foundCondition.signalAll();
                    }
                }
            } finally {
                lock.unlock();
            }
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
            lock.lock();
            try {
                while (pattern != null) {
                    if (done) {
                        throw new IllegalStateException("Pattern not found");
                    }
                    foundCondition.awaitUninterruptibly();
                }
                pattern = nextPattern;
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * A listener that writes all input data to a file.
     */
    class Write implements OutputListener, Closeable {
        private static final Logger LOG = LoggerFactory.getLogger(Write.class);

        private final OutputStream outputStream;

        public Write(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        @Override
        public synchronized void onData(ByteBuffer data) {
            try {
                outputStream.write(data.array(), data.arrayOffset() + data.position(), data.remaining());
            } catch (ClosedChannelException ignored) {
            } catch (IOException e) {
                LOG.error("Failed to write data", e);
            }
        }

        public synchronized void println(@NonNull String msg) {
            try {
                outputStream.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
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
