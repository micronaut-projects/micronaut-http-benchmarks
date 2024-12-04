package io.micronaut.benchmark.loadgen.oci;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Throttles for OCI API endpoints.
 */
public final class Throttle {
    public static final Throttle IDENTITY = new Throttle(Duration.ofSeconds(5));
    public static final Throttle COMPUTE = new Throttle(Duration.ofSeconds(1));
    public static final Throttle VCN = new Throttle(Duration.ofMillis(500));

    private final long delay;

    private long last = 0;

    private Throttle(Duration delay) {
        this.delay = delay.toNanos();
    }

    public void take() throws InterruptedException {
        while (true) {
            long remaining;
            synchronized (this) {
                long now = System.nanoTime();
                remaining = last - now + delay;
                if (remaining <= 0) {
                    last = now;
                    return;
                }
            }
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }

    public void takeUninterruptibly() {
        boolean interrupted = false;
        while (true) {
            try {
                take();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
