package io.micronaut.benchmark.loadgen.oci.resource;

import io.micronaut.benchmark.loadgen.oci.AbstractInfrastructure;
import io.micronaut.benchmark.loadgen.oci.OciLocation;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.util.functional.ThrowingSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

public abstract class AbstractSimpleResource<P> extends PhasedResource<P> {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractSimpleResource.class);
    private boolean managing = false;
    private String ocid;

    private final P provisioning;
    private final P available;
    @Nullable
    private final P failed;
    private final P terminating;
    private final P terminated;

    private final List<PhaseLock> locks = new ArrayList<>();

    public AbstractSimpleResource(
            P provisioning, P available, P terminating, P terminated,
            ResourceContext context) {
        this(provisioning, available, null, terminating, terminated, context);
    }

    /**
     * @param failed A phase the resource may enter instead of {@code available}. Dependents waiting for
     *               {@code available} will fail, but the resource is still deleted.
     */
    public AbstractSimpleResource(
            P provisioning, P available, @Nullable P failed, P terminating, P terminated,
            ResourceContext context) {
        super(context);
        this.provisioning = provisioning;
        this.available = available;
        this.failed = failed;
        this.terminating = terminating;
        this.terminated = terminated;
    }

    public final void dependOn(List<PhaseLock> locks) {
        if (managing) {
            throw new IllegalStateException("Can only add dependencies before it's managed");
        }
        this.locks.addAll(locks);
        locks.stream().flatMap(PhaseLock::uuids).forEach(l -> context.log(new DependencyEvent(uuid, l)));
    }

    public List<PhaseLock> require() {
        return List.of(lock(available));
    }

    @Override
    protected List<P> phases() {
        return Stream.of(provisioning, available, failed, terminating, terminated).filter(Objects::nonNull).toList();
    }

    public final String ocid() {
        if (ocid == null) {
            throw new IllegalStateException("OCID not yet available");
        }
        return ocid;
    }

    protected final void awaitLocks() throws InterruptedException {
        PhaseLock.awaitAll(locks);
    }

    protected final void manageNew(OciLocation location, ThrowingSupplier<CreationResult<P>, Exception> create) throws Exception {
        String ocid = null;
        try {
            awaitLocks();

            CreationResult<P> result = create.get();
            ocid = result.ocid();
            this.ocid = ocid;
            setPhase(result.phase());
        } finally {
            if (ocid == null) {
                for (PhaseLock lock : locks) {
                    lock.close();
                }
                if (terminated != null) {
                    setPhase(terminated);
                }
            }
        }
        manageExisting(location, ocid);
    }

    public final void manageExisting(OciLocation location, String ocid) throws Exception {
        if (managing) {
            throw new IllegalStateException("Resource is already managed");
        }
        managing = true;
        this.ocid = ocid;
        try {
            getPoller(location).subscribeUntil(ocid, this, available);
            if (awaitPhaseOrPast(available) == failed) {
                LOG.warn("{} {} is in phase {}", this, ocid, failed);
            }

            if (LOG.isDebugEnabled()) {
                synchronized (this) {
                    if (super.locks.isEmpty()) {
                        LOG.debug("No locks for {}", this);
                    }
                }
            }
            P current = awaitUnlocked(available, failed == null ? available : failed);
            if (current == available || (failed != null && current == failed)) {
                AbstractInfrastructure.retry(() -> {
                    LOG.info("Deleting {} {}", this, ocid);
                    delete(location, ocid);
                    return null;
                });
            }

            if (terminated != null) {
                getPoller(location).subscribeUntil(ocid, this, terminated);
                awaitPhase(terminated);
            }
        } finally {
            for (PhaseLock lock : locks) {
                lock.close();
            }
        }
    }

    protected abstract void delete(OciLocation location, String ocid);

    protected abstract PhasePoller<String, P> getPoller(OciLocation location);

    protected record CreationResult<P>(String ocid, P phase) {
    }

    public record DependencyEvent(
            UUID resource,
            UUID lock
    ) implements ResourceContext.LogEvent {
    }
}
