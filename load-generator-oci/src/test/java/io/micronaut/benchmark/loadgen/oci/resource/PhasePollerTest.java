package io.micronaut.benchmark.loadgen.oci.resource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhasePollerTest {
    @Test
    void replacementRemainsSubscribedWhenAnInFlightPollCompletesThePreviousSubscription() throws Exception {
        CountDownLatch oldPollStarted = new CountDownLatch(1);
        CountDownLatch releaseOldPoll = new CountDownLatch(1);
        AtomicInteger polls = new AtomicInteger();
        PhasePoller<String, Phase> poller = PhasePoller.create(key -> {
            if (polls.getAndIncrement() == 0) {
                oldPollStarted.countDown();
                try {
                    assertTrue(releaseOldPoll.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return Phase.COMPLETE;
            }
            return Phase.TARGET;
        }, List::<String>of, summary -> summary, summary -> Phase.TARGET);
        ResourceContext context = new ResourceContext(null);
        TestResource oldResource = new TestResource(context);
        poller.subscribeUntil("resource", oldResource, Phase.COMPLETE);

        try (ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> oldPoll = executor.submit(poller::poll);
            assertTrue(oldPollStarted.await(5, TimeUnit.SECONDS));

            TestResource replacement = new TestResource(context);
            poller.subscribeUntil("resource", replacement, Phase.TARGET);
            releaseOldPoll.countDown();
            oldPoll.get(5, TimeUnit.SECONDS);

            poller.poll();

            assertEquals(Phase.TARGET, replacement.getCurrentPhase());
        }
    }

    private enum Phase {
        TARGET,
        COMPLETE
    }

    private static final class TestResource extends PhasedResource<Phase> {
        private TestResource(ResourceContext context) {
            super(context);
        }

        @Override
        protected List<Phase> phases() {
            return List.of(Phase.TARGET, Phase.COMPLETE);
        }
    }
}
