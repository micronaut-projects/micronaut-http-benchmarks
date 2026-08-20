package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.objectstorage.model.CreatePreauthenticatedRequestDetails;
import com.oracle.bmc.objectstorage.model.PreauthenticatedRequest;
import com.oracle.bmc.objectstorage.requests.CreatePreauthenticatedRequestRequest;
import io.micronaut.benchmark.loadgen.oci.NixCacheAccess;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public final class NixosCacheResource extends PhasedResource<NixosCacheResource.Phase> {
    private static final Logger LOG = LoggerFactory.getLogger(NixosCacheResource.class);

    private final String namespace;
    private final String bucket;
    private final String path;
    private final String installable;
    private final boolean dynamicPgo;
    private NixCacheAccess cacheAccess;
    private boolean publicationSignaled;
    private boolean publicationCancelled;

    public NixosCacheResource(ResourceContext context, String namespace, String bucket, String path, String installable, boolean dynamicPgo) {
        super(context);
        this.namespace = namespace;
        this.bucket = bucket;
        this.path = path;
        this.installable = installable;
        this.dynamicPgo = dynamicPgo;
    }

    @Override
    protected List<Phase> phases() {
        return Arrays.asList(Phase.values());
    }

    public List<PhaseLock> require() {
        return List.of(lock(Phase.Available));
    }

    public List<PhaseLock> requirePublished() {
        return List.of(lock(Phase.Published));
    }

    public void manage() throws Exception {
        setPhase(Phase.Uploading);
        try {
            URI writeCacheUri = buildPreauthenticatedRequest(CreatePreauthenticatedRequestDetails.AccessType.AnyObjectReadWrite);
            URI readCacheUri = buildPreauthenticatedRequest(CreatePreauthenticatedRequestDetails.AccessType.AnyObjectRead);
            String defaultOutput = dynamicPgo
                    ? null
                    : context.clients.nix().resolveOutput(
                            new OutputListener.Log(LOG, Level.DEBUG),
                            installable
                    ).toString();
            cacheAccess = new NixCacheAccess(installable, defaultOutput, readCacheUri, writeCacheUri);
            setPhase(Phase.Available);
            if (dynamicPgo) {
                return;
            }
            awaitPublicationSignal();
            NixCacheAccess cache = cacheAccess();
            Path output = context.clients.nix().buildAndUploadOutputCache(
                    new OutputListener.Log(LOG, Level.DEBUG),
                    cache.writeUri(),
                    cache.installable()
            );
            if (!output.toString().equals(cache.requireDefaultOutput())) {
                throw new IllegalStateException("Resolved output does not match built output for " + cache.installable());
            }
            setPhase(Phase.Published);
        } catch (InterruptedException e) {
            setPhase(Phase.Failed);
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            setPhase(Phase.Failed);
            throw e;
        }
    }

    private URI buildPreauthenticatedRequest(CreatePreauthenticatedRequestDetails.AccessType accessType) {
        PreauthenticatedRequest preauthenticatedRequest = context.clients.objectStorage().createPreauthenticatedRequest(CreatePreauthenticatedRequestRequest.builder()
                .namespaceName(namespace)
                .bucketName(bucket)
                .createPreauthenticatedRequestDetails(CreatePreauthenticatedRequestDetails.builder()
                        .name("micronaut-benchmark nixos cache access " + UUID.randomUUID())
                        .accessType(accessType)
                        .objectName(path)
                        .timeExpires(Date.from(Instant.now().plus(1, ChronoUnit.DAYS)))
                        .bucketListingAction(PreauthenticatedRequest.BucketListingAction.ListObjects)
                        .build())
                .build()).getPreauthenticatedRequest();

        return URI.create(context.clients.objectStorage().getEndpoint() + preauthenticatedRequest.getAccessUri() + path);
    }

    public NixCacheAccess cacheAccess() {
        Phase phase = getCurrentPhase();
        if (phase == Phase.Failed || compare(phase, Phase.Available) < 0) {
            throw new IllegalStateException("Cache not yet available");
        }
        return cacheAccess;
    }

    public NixCacheAccess awaitAvailable() throws InterruptedException {
        Phase phase = awaitPhaseOrPast(Phase.Available);
        if (phase == Phase.Failed) {
            throw new IllegalStateException("Cache failed");
        }
        return cacheAccess;
    }

    public NixCacheAccess awaitPublished() throws InterruptedException {
        Phase phase = awaitPhaseOrPast(Phase.Published);
        if (phase == Phase.Failed) {
            throw new IllegalStateException("Cache publication failed");
        }
        return cacheAccess;
    }

    public synchronized void signalPublication() {
        if (dynamicPgo) {
            throw new IllegalStateException("Dynamic PGO caches have no default output");
        }
        publicationSignaled = true;
        notifyAll();
    }

    public synchronized void cancelPublicationWait() {
        if (dynamicPgo || publicationSignaled || publicationCancelled) {
            return;
        }
        publicationCancelled = true;
        notifyAll();
    }

    private synchronized void awaitPublicationSignal() throws InterruptedException {
        if (publicationCancelled) {
            throw new InterruptedException("Cache publication cancelled");
        }
        while (!publicationSignaled) {
            wait();
            if (publicationCancelled) {
                throw new InterruptedException("Cache publication cancelled");
            }
        }
    }

    public boolean dynamicPgo() {
        return dynamicPgo;
    }

    public enum Phase {
        Uploading,
        Available,
        Published,
        Failed,
    }
}
