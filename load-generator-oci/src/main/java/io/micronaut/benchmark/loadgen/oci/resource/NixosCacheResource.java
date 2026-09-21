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
    private NixCacheAccess cacheAccess;
    private boolean publicationSignaled;
    private boolean publicationCancelled;

    public NixosCacheResource(ResourceContext context, String namespace, String bucket, String path, String installable) {
        super(context);
        this.namespace = namespace;
        this.bucket = bucket;
        this.path = path;
        this.installable = installable;
    }

    @Override
    protected List<Phase> phases() {
        return Arrays.asList(Phase.values());
    }

    public List<PhaseLock> requirePublished() {
        return List.of(lock(Phase.Published));
    }

    public void manage() throws Exception {
        setPhase(Phase.Uploading);
        try {
            URI writeCacheUri = buildPreauthenticatedRequest(CreatePreauthenticatedRequestDetails.AccessType.AnyObjectReadWrite);
            URI readCacheUri = buildPreauthenticatedRequest(CreatePreauthenticatedRequestDetails.AccessType.AnyObjectRead);
            String defaultOutput = context.clients.nix().resolveOutput(
                    new OutputListener.Log(LOG, Level.DEBUG), installable).toString();
            cacheAccess = new NixCacheAccess(defaultOutput, readCacheUri);
            setPhase(Phase.Available);
            awaitPublicationSignal();
            NixCacheAccess cache = cacheAccess();
            Path output = context.clients.nix().buildAndUploadOutputCache(
                    new OutputListener.Log(LOG, Level.DEBUG),
                    writeCacheUri,
                    installable
            );
            if (!output.toString().equals(cache.defaultOutput())) {
                throw new IllegalStateException("Resolved output does not match built output for " + installable);
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

    public NixCacheAccess awaitPublished() throws InterruptedException {
        Phase phase = awaitPhaseOrPast(Phase.Published);
        if (phase == Phase.Failed) {
            throw new IllegalStateException("Cache publication failed");
        }
        return cacheAccess;
    }

    public synchronized void signalPublication() {
        publicationSignaled = true;
        notifyAll();
    }

    public synchronized void cancelPublicationWait() {
        if (publicationSignaled || publicationCancelled) {
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

    public enum Phase {
        Uploading,
        Available,
        Published,
        Failed,
    }
}
