package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.objectstorage.model.CreatePreauthenticatedRequestDetails;
import com.oracle.bmc.objectstorage.model.PreauthenticatedRequest;
import com.oracle.bmc.objectstorage.requests.CreatePreauthenticatedRequestRequest;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.net.URI;
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
    private final boolean derivation;

    public NixosCacheResource(ResourceContext context, String namespace, String bucket, String path, String installable, boolean derivation) {
        super(context);
        this.namespace = namespace;
        this.bucket = bucket;
        this.path = path;
        this.installable = installable;
        this.derivation = derivation;
    }

    @Override
    protected List<Phase> phases() {
        return Arrays.asList(Phase.values());
    }

    public List<PhaseLock> require() {
        return List.of(lock(Phase.Available));
    }

    public void manage() throws Exception {
        context.clients.nix().uploadCache(
                new OutputListener.Log(LOG, Level.INFO),
                buildPreauthenticatedRequest(CreatePreauthenticatedRequestDetails.AccessType.AnyObjectReadWrite),
                installable,
                derivation
        );
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

        return URI.create(context.clients.objectStorage().getEndpoint() + preauthenticatedRequest.getAccessUri());
    }

    public URI buildCacheUri() {
        if (getCurrentPhase() != Phase.Available) {
            throw new IllegalStateException("Cache not yet available");
        }
        return buildPreauthenticatedRequest(CreatePreauthenticatedRequestDetails.AccessType.AnyObjectRead);
    }

    public enum Phase {
        Uploading,
        Available,
    }
}
