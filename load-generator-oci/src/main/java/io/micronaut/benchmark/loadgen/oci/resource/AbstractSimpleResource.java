package io.micronaut.benchmark.loadgen.oci.resource;

import io.micronaut.benchmark.loadgen.oci.AbstractInfrastructure;
import io.micronaut.benchmark.loadgen.oci.OciLocation;
import io.micronaut.core.util.functional.ThrowingSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractSimpleResource<P> extends PhasedResource<P> {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractSimpleResource.class);
    private boolean managing = false;
    private String ocid;

    private final P provisioning;
    private final P available;
    private final P terminating;
    private final P terminated;

    private final List<PhaseLock> locks = new ArrayList<>();

    public AbstractSimpleResource(
            P provisioning, P available, P terminating, P terminated,
            ResourceContext context) {
        super(context);
        this.provisioning = provisioning;
        this.available = available;
        this.terminating = terminating;
        this.terminated = terminated;
    }

    public final void dependOn(List<PhaseLock> locks) {
        if (managing) {
            throw new IllegalStateException("Can only add dependencies before it's managed");
        }
        this.locks.addAll(locks);
    }

    public List<PhaseLock> require() {
        return List.of(lock(available));
    }

    @Override
    protected List<P> phases() {
        return List.of(provisioning, available, terminating, terminated);
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

    protected final void manageNew(OciLocation location, ThrowingSupplier<String, Exception> create) throws Exception {
        String ocid = null;
        try {
            awaitLocks();

            ocid = create.get();
        } finally {
            if (ocid == null) {
                for (PhaseLock lock : locks) {
                    lock.close();
                }
                setPhase(terminated);
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
            awaitPhaseOrPast(available);

            if (LOG.isDebugEnabled()) {
                synchronized (this) {
                    if (super.locks.isEmpty()) {
                        LOG.debug("No locks for {}", this);
                    }
                }
            }
            if (awaitUnlocked(available) == available) {
                AbstractInfrastructure.retry(() -> {
                    LOG.info("Deleting {} {}", this, ocid);
                    delete(location, ocid);
                    return null;
                });
            }

            getPoller(location).subscribeUntil(ocid, this, terminated);
            awaitPhase(terminated);
        } finally {
            for (PhaseLock lock : locks) {
                lock.close();
            }
        }
    }

    protected abstract void delete(OciLocation location, String ocid);

    protected abstract PhasePoller<String, P> getPoller(OciLocation location);
}
