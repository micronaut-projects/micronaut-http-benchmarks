package io.micronaut.benchmark.loadgen.oci.resource;

import io.micronaut.core.annotation.Nullable;
import io.netty.util.internal.PlatformDependent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public abstract class PhasedResource<P> {
    private static final Logger LOG = LoggerFactory.getLogger(PhasedResource.class);

    protected final UUID uuid = UUID.randomUUID();

    protected final ResourceContext context;

    final Map<P, Integer> locks = new HashMap<>();
    private P currentPhase;

    @Nullable
    private String name;

    protected PhasedResource(ResourceContext context) {
        this.context = context;
        context.log(new CreateResourceEvent(uuid, getClass().getSimpleName()));
    }

    public final void name(String name) {
        this.name = name;
        context.log(new NameEvent(uuid, name));
    }

    public synchronized P getCurrentPhase() {
        return currentPhase;
    }

    protected abstract List<P> phases();

    public final int compare(P a, P b) {
        List<P> phases = phases();
        int indexA = a == null ? -1 : phases.indexOf(a);
        int indexB = b == null ? -1 : phases.indexOf(b);
        return Integer.compare(indexA, indexB);
    }

    protected final void awaitPhase(P phase) throws InterruptedException {
        P current = awaitPhaseOrPast(phase);
        if (current != phase) {
            throw new IllegalStateException("Already in phase " + current + ", past phase " + phase);
        }
    }

    protected final synchronized P awaitPhaseOrPast(P phase) throws InterruptedException {
        P current;
        while (true) {
            current = this.currentPhase;
            if (compare(current, phase) >= 0) {
                break;
            }
            wait();
        }
        return current;
    }

    public final synchronized void setPhase(P phase) {
        if (this.currentPhase == phase) {
            return;
        }
        if (compare(currentPhase, phase) > 0) {
            throw new IllegalStateException("Already in phase " + this.currentPhase + ", past phase " + phase);
        }
        this.currentPhase = phase;
        context.log(new PhaseEvent(uuid, phases(), phase));
        notifyAll();
    }

    protected final synchronized P awaitUnlocked(P phase) throws InterruptedException {
        P current;
        boolean first = true;
        while (true) {
            current = this.currentPhase;
            if (compare(current, phase) > 0) {
                break;
            }
            Integer l = locks.get(phase);
            if (l == null || l == 0) {
                break;
            }
            LOG.trace("awaitUnlocked {} {} {}", getClass().getSimpleName(), phase, l);
            first = false;
            wait();
        }
        if (!first) {
            LOG.trace("/awaitUnlocked {} {}", getClass().getSimpleName(), phase);
        }
        return current;
    }

    protected final synchronized PhaseLock lock(P phase) {
        LOG.trace("lock {} {}", getClass().getSimpleName(), phase, new Exception());
        locks.compute(phase, (_, v) -> v == null ? 1 : v + 1);
        return new PhaseLockImpl(phase);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + "]";
    }

    public sealed interface PhaseLock extends AutoCloseable {
        Stream<UUID> uuids();

        void await() throws InterruptedException;

        @Override
        void close();

        static PhaseLock combine(List<PhaseLock> locks) {
            return new CompositePhaseLock(locks);
        }

        static void awaitAll(List<PhaseLock> locks) throws InterruptedException {
            combine(locks).await();
        }
    }

    private final class PhaseLockImpl implements PhaseLock {
        private final UUID uuid = UUID.randomUUID();
        private final P phase;
        private boolean closed = false;

        private PhaseLockImpl(P phase) {
            this.phase = phase;
            context.log(new CreateLockEvent(PhasedResource.this.uuid, uuid, phase));
        }

        @Override
        public Stream<UUID> uuids() {
            return Stream.of(uuid);
        }

        @Override
        public void await() throws InterruptedException {
            awaitPhase(phase);
        }

        @Override
        public void close() {
            synchronized (PhasedResource.this) {
                if (closed) {
                    return;
                }
                closed = true;
                //noinspection DataFlowIssue
                if (locks.compute(phase, (_, v) -> v - 1) == 0) {
                    PhasedResource.this.notifyAll();
                }
            }
            context.log(new CloseLockEvent(PhasedResource.this.uuid, uuid, phase));
        }
    }

    public record CreateLockEvent(
            UUID resource,
            UUID lock,
            Object phase
    ) implements ResourceContext.LogEvent {
    }

    public record CloseLockEvent(
            UUID resource,
            UUID lock,
            Object phase
    ) implements ResourceContext.LogEvent {
    }

    public record PhaseEvent(
            UUID resource,
            List<?> phases,
            Object phase
    ) implements ResourceContext.LogEvent {
    }

    public record CreateResourceEvent(
            UUID resource,
            String simpleClassName
    ) implements ResourceContext.LogEvent {
    }

    public record NameEvent(
            UUID resource,
            String name
    ) implements ResourceContext.LogEvent {
    }

    private record CompositePhaseLock(List<PhaseLock> members) implements PhaseLock {
        @Override
        public Stream<UUID> uuids() {
            return members.stream().flatMap(PhaseLock::uuids);
        }

        @Override
        public void await() throws InterruptedException {
            try {
                CompletableFuture.allOf(
                        members.stream()
                                .map(pl -> CompletableFuture.runAsync(() -> {
                                    try {
                                        pl.await();
                                    } catch (InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }
                                }, Executors.newVirtualThreadPerTaskExecutor()))
                                .toArray(CompletableFuture[]::new)
                ).get();
            } catch (ExecutionException e) {
                PlatformDependent.throwException(e.getCause());
            }
        }

        @Override
        public void close() {
            for (PhaseLock member : members) {
                member.close();
            }
        }
    }
}
