package io.micronaut.benchmark.loadgen.oci.resource;

import io.micronaut.benchmark.loadgen.oci.OciLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractSimpleResourceTest {
    @TempDir
    Path temporary;

    @Test
    void failedCreationFailsDependentsAndIsDeleted() throws Exception {
        TestResource resource = new TestResource(new ResourceContext(null, temporary), State.Creating);
        PhasedResource.PhaseLock dependent = PhasedResource.PhaseLock.combine(resource.require());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> managed = executor.submit(() -> {
                resource.manageExisting(null, "ocid");
                return null;
            });
            Future<?> awaited = executor.submit(() -> {
                try {
                    dependent.await();
                } finally {
                    dependent.close();
                }
                return null;
            });
            resource.poller.poll();
            resource.remoteState = State.Failed;
            pollUntilDone(resource, managed);

            assertTrue(resource.deleted);
            assertEquals(State.Deleted, resource.getCurrentPhase());
            Exception e = assertThrows(Exception.class, () -> awaited.get(5, TimeUnit.SECONDS));
            assertTrue(e.getCause().getCause() instanceof IllegalStateException, e::toString);
        }
    }

    @Test
    void leftoverFailedResourceIsDeleted() throws Exception {
        TestResource resource = new TestResource(new ResourceContext(null, temporary), State.Failed);
        resource.setPhase(State.Failed);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> managed = executor.submit(() -> {
                resource.manageExisting(null, "ocid");
                return null;
            });
            pollUntilDone(resource, managed);

            assertTrue(resource.deleted);
        }
    }

    @Test
    void transientAndUnknownStatesDoNotThrow() throws Exception {
        TestResource resource = new TestResource(new ResourceContext(null, temporary), State.Active);
        resource.setPhase(State.Active);
        resource.setPhase(State.Updating);
        resource.setPhase(State.Unknown);
        resource.setPhase(State.Active);
        assertEquals(State.Active, resource.getCurrentPhase());
    }

    private static void pollUntilDone(TestResource resource, Future<?> future) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!future.isDone()) {
            if (System.nanoTime() > deadline) {
                future.cancel(true);
                throw new TimeoutException();
            }
            resource.poller.poll();
            Thread.sleep(10);
        }
        future.get();
    }

    private enum State {
        Creating,
        Updating,
        Active,
        Failed,
        Deleting,
        Deleted,
        Unknown
    }

    private static final class TestResource extends AbstractSimpleResource<State> {
        volatile State remoteState;
        final PhasePoller<String, State> poller = PhasePoller.create(k -> this.remoteState, List::<String>of, s -> s, s -> State.Unknown);
        volatile boolean deleted;

        TestResource(ResourceContext context, State remoteState) {
            super(State.Creating, State.Active, State.Failed, State.Deleting, State.Deleted, context);
            this.remoteState = remoteState;
        }

        @Override
        protected State normalizePhase(State phase) {
            return phase == State.Updating ? State.Active : phase;
        }

        @Override
        protected void delete(OciLocation location, String ocid) {
            deleted = true;
            remoteState = State.Deleted;
        }

        @Override
        protected PhasePoller<String, State> getPoller(OciLocation location) {
            return poller;
        }
    }
}
