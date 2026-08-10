package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.requests.HeadObjectRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import io.micronaut.core.util.functional.ThrowingConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

public final class StorageObjectResource extends PhasedResource<StorageObjectResource.Phase> {
    private static final Logger LOG = LoggerFactory.getLogger(StorageObjectResource.class);

    private final String namespace;
    private final String bucket;
    private final String objectName;
    private final ObjectBuilder builder;
    private String hash;

    public StorageObjectResource(ResourceContext context, String namespace, String bucket, String objectName, ObjectBuilder builder) {
        super(context);
        this.namespace = namespace;
        this.bucket = bucket;
        this.objectName = objectName;
        this.builder = builder;
    }

    public void manage() throws Exception {
        setPhase(Phase.Building);

        LOG.info("Building file {}/{}", bucket, objectName);
        builder.build(path -> {
            synchronized (this) {
                if (getCurrentPhase() != Phase.Building) {
                    throw new IllegalStateException("Upload closure called multiple times");
                }
                setPhase(Phase.Uploading);
            }

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    md.update(buffer, 0, read);
                }
            }
            String expectedSha256 = Base64.getEncoder().encodeToString(md.digest());

            String existingHash;
            try {
                existingHash = context.clients.objectStorage().headObject(HeadObjectRequest.builder()
                        .namespaceName(namespace)
                        .bucketName(bucket)
                        .objectName(objectName)
                        .build()).getOpcContentSha256();
            } catch (BmcException be) {
                if (be.getStatusCode() == 404) {
                    existingHash = null;
                } else {
                    throw be;
                }
            }

            if (expectedSha256.equals(existingHash)) {
                LOG.info("File {}/{} already uploaded ({})", bucket, objectName, existingHash);
            } else {
                LOG.info("Uploading file {}/{} ({})", bucket, objectName, existingHash);
                context.clients.objectStorage().putObject(PutObjectRequest.builder()
                        .namespaceName(namespace)
                        .bucketName(bucket)
                        .objectName(objectName)
                        .opcContentSha256(expectedSha256)
                        .contentLength(Files.size(path))
                        .putObjectBody(Files.newInputStream(path))
                        .build());
                LOG.info("Uploaded file {}/{} ({})", bucket, objectName, existingHash);
            }

            hash = expectedSha256;
            setPhase(Phase.Available);
        });

        if (getCurrentPhase() != Phase.Available) {
            throw new IllegalStateException("Upload closure not called");
        }
    }

    public String getHash() {
        if (getCurrentPhase() != Phase.Available) {
            throw new IllegalStateException("Object not yet uploaded");
        }
        return hash;
    }

    @Override
    protected List<Phase> phases() {
        return Arrays.asList(Phase.values());
    }

    public List<PhaseLock> require() {
        return List.of(lock(Phase.Available));
    }

    public enum Phase {
        Building,
        Uploading,
        Available,
    }

    public interface ObjectBuilder {
        void build(ThrowingConsumer<Path, Exception> upload) throws Exception;
    }
}
