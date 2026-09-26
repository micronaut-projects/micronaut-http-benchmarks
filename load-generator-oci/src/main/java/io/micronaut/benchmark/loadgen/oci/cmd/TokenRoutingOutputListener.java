package io.micronaut.benchmark.loadgen.oci.cmd;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

public final class TokenRoutingOutputListener implements OutputListener {
    private static final byte[] SWITCH_MESSAGE_BYTES = "\n--- OUTPUT SWITCHED TO THIS LOG ---\n".getBytes(StandardCharsets.US_ASCII);
    private static final ByteBuffer SWITCH_MESSAGE = ByteBuffer.wrap(SWITCH_MESSAGE_BYTES);

    private final OutputListener defaultTarget;
    private OutputListener activeTarget;
    private Switch pendingSwitch;
    private byte[] tokenPrefix = new byte[0];
    private boolean completed;

    public TokenRoutingOutputListener(OutputListener defaultTarget) {
        this.defaultTarget = Objects.requireNonNull(defaultTarget, "defaultTarget");
        activeTarget = defaultTarget;
    }

    public synchronized Switch switchOn(String token, OutputListener target) {
        ensureOpen();
        if (pendingSwitch != null) {
            throw new IllegalStateException("An output switch is already pending");
        }
        pendingSwitch = new Switch(token, Objects.requireNonNull(target, "target"));
        return pendingSwitch;
    }

    @Override
    public synchronized void onData(ByteBuffer data) {
        ensureOpen();
        if (pendingSwitch == null) {
            activeTarget.onData(data);
            return;
        }
        byte[] input = new byte[tokenPrefix.length + data.remaining()];
        System.arraycopy(tokenPrefix, 0, input, 0, tokenPrefix.length);
        data.get(input, tokenPrefix.length, data.remaining());
        tokenPrefix = new byte[0];
        route(input);
    }

    @Override
    public synchronized void onComplete() {
        if (completed) {
            return;
        }
        forward(tokenPrefix, 0, tokenPrefix.length);
        tokenPrefix = new byte[0];
        completed = true;
        if (pendingSwitch != null) {
            pendingSwitch.collectorCompleted();
            pendingSwitch = null;
        }
        defaultTarget.onComplete();
    }

    private void route(byte[] input) {
        Switch armed = pendingSwitch;
        int tokenLength = armed.token.length;
        int index = 0;
        while (index < input.length) {
            int remaining = input.length - index;
            if (remaining >= tokenLength && matches(armed.token, input, index, tokenLength)) {
                forward(input, 0, index);
                activeTarget = armed.target;
                emitSwitchMessage();
                forward(input, index + tokenLength, remaining - tokenLength);
                pendingSwitch = null;
                armed.markObserved();
                return;
            }
            if (remaining < tokenLength && matches(armed.token, input, index, remaining)) {
                forward(input, 0, index);
                tokenPrefix = Arrays.copyOfRange(input, index, input.length);
                return;
            }
            index++;
        }
        forward(input, 0, input.length);
    }

    private void forward(byte[] bytes, int offset, int length) {
        if (length > 0) {
            activeTarget.onData(ByteBuffer.wrap(bytes, offset, length));
        }
    }

    private void emitSwitchMessage() {
        activeTarget.onData(SWITCH_MESSAGE.duplicate());
    }

    private void ensureOpen() {
        if (completed) {
            throw new IllegalStateException("Listener is complete");
        }
    }

    private static boolean matches(byte[] expected, byte[] actual, int offset, int length) {
        if (length > expected.length || offset + length > actual.length) {
            return false;
        }
        for (int index = 0; index < length; index++) {
            if (expected[index] != actual[offset + index]) {
                return false;
            }
        }
        return true;
    }

    public final class Switch {
        private final byte[] token;
        private final OutputListener target;
        private boolean observed;
        private boolean cancelled;
        private boolean collectorCompleted;

        private Switch(String token, OutputListener target) {
            this.token = asciiToken(token);
            this.target = target;
        }

        public synchronized boolean observed() {
            return observed;
        }

        public void await(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            boolean interrupted = false;
            long remaining = timeout.toNanos();
            try {
                synchronized (this) {
                    while (!observed) {
                        if (cancelled || collectorCompleted) {
                            throw new IllegalStateException("Output collection ended before token observation");
                        }
                        if (remaining <= 0) {
                            throw new IllegalStateException("Timed out waiting for token observation");
                        }
                        long before = System.nanoTime();
                        try {
                            wait(remaining / 1_000_000, (int) (remaining % 1_000_000));
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                        remaining -= System.nanoTime() - before;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        public void cancel(OutputListener restoreTarget) {
            synchronized (TokenRoutingOutputListener.this) {
                if (pendingSwitch == this) {
                    pendingSwitch = null;
                }
                activeTarget = Objects.requireNonNull(restoreTarget, "restoreTarget");
                forward(tokenPrefix, 0, tokenPrefix.length);
                tokenPrefix = new byte[0];
            }
            synchronized (this) {
                cancelled = true;
                notifyAll();
            }
        }

        private synchronized void markObserved() {
            observed = true;
            notifyAll();
        }

        private synchronized void collectorCompleted() {
            collectorCompleted = true;
            notifyAll();
        }
    }

    private static byte[] asciiToken(String token) {
        Objects.requireNonNull(token, "token");
        byte[] bytes = token.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length == 0 || !token.equals(new String(bytes, StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Token must be non-empty ASCII: " + token);
        }
        return bytes;
    }
}
