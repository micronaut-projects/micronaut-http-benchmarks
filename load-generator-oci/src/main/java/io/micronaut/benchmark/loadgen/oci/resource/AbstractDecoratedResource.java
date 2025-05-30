package io.micronaut.benchmark.loadgen.oci.resource;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractDecoratedResource extends PhasedResource<AbstractDecoratedResource.Phase> {
    private boolean managing = false;
    private final List<PhaseLock> locks = new ArrayList<>();

    public AbstractDecoratedResource(ResourceContext context) {
        super(context);
    }

    @Override
    protected final List<Phase> phases() {
        return List.of(Phase.values());
    }

    public final void manage() throws Exception {
        launchDependencies();

        if (managing) {
            throw new IllegalStateException("Resource is already managed");
        }
        managing = true;
        try {
            setPhase(Phase.Waiting);
            PhaseLock.awaitAll(locks);

            setPhase(Phase.Initializing);
            setUp();

            setPhase(Phase.Active);
            awaitUnlocked(Phase.Active);

            setPhase(Phase.Terminating);
            tearDown();

        } finally {
            unlock();
            for (PhaseLock lock : locks) {
                lock.close();
            }
            setPhase(Phase.Terminated);
        }
    }

    protected void unlock() {
    }

    protected void launchDependencies() throws Exception {
    }

    protected void setUp() throws Exception {
    }

    protected void tearDown() {
    }

    public final void dependOn(List<PhaseLock> locks) {
        if (managing) {
            throw new IllegalStateException("Can only add dependencies before it's managed");
        }
        this.locks.addAll(locks);
    }

    public List<PhaseLock> require() {
        return List.of(lock(Phase.Active));
    }

    public enum Phase {
        Waiting,
        Initializing,
        Active,
        Terminating,
        Terminated
    }
}
