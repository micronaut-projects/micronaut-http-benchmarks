package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.Region;
import com.oracle.bmc.bastion.model.CreatePortForwardingSessionTargetResourceDetails;
import com.oracle.bmc.bastion.model.CreateSessionDetails;
import com.oracle.bmc.bastion.model.PublicKeyDetails;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.BooleanImageCapabilitySchemaDescriptor;
import com.oracle.bmc.core.model.ComputeGlobalImageCapabilitySchemaSummary;
import com.oracle.bmc.core.model.ComputeImageCapabilitySchema;
import com.oracle.bmc.core.model.CreateComputeImageCapabilitySchemaDetails;
import com.oracle.bmc.core.model.CreateImageDetails;
import com.oracle.bmc.core.model.CreateVnicDetails;
import com.oracle.bmc.core.model.EnumStringImageCapabilitySchemaDescriptor;
import com.oracle.bmc.core.model.Image;
import com.oracle.bmc.core.model.ImageCapabilitySchemaDescriptor;
import com.oracle.bmc.core.model.ImageSourceDetails;
import com.oracle.bmc.core.model.ImageSourceViaObjectStorageTupleDetails;
import com.oracle.bmc.core.model.InstanceAgentPluginConfigDetails;
import com.oracle.bmc.core.model.InstanceOptions;
import com.oracle.bmc.core.model.InstanceSourceViaImageDetails;
import com.oracle.bmc.core.model.LaunchInstanceAgentConfigDetails;
import com.oracle.bmc.core.model.LaunchInstanceDetails;
import com.oracle.bmc.core.model.LaunchInstanceShapeConfigDetails;
import com.oracle.bmc.core.model.LaunchOptions;
import com.oracle.bmc.core.requests.DeleteImageRequest;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.core.requests.ListComputeGlobalImageCapabilitySchemasRequest;
import com.oracle.bmc.core.requests.ListVnicAttachmentsRequest;
import io.micronaut.benchmark.api.InstanceType;
import io.micronaut.benchmark.api.Nix;
import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.resource.AbstractDecoratedResource;
import io.micronaut.benchmark.loadgen.oci.resource.BastionSessionResource;
import io.micronaut.benchmark.loadgen.oci.resource.ComputeImageCapabilitySchemaResource;
import io.micronaut.benchmark.loadgen.oci.resource.ComputeResource;
import io.micronaut.benchmark.loadgen.oci.resource.CustomImageResource;
import io.micronaut.benchmark.loadgen.oci.resource.NixosCacheResource;
import io.micronaut.benchmark.loadgen.oci.resource.PhasedResource;
import io.micronaut.benchmark.loadgen.oci.resource.ResourceContext;
import io.micronaut.benchmark.loadgen.oci.resource.StorageObjectResource;
import io.micronaut.benchmark.loadgen.oci.resource.SubnetResource;
import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class handles provisioning of compute instances (VMs) according to configured instance settings.
 */
@Singleton
public final class Compute {
    private static final Logger LOG = LoggerFactory.getLogger(Compute.class);
    private static final String BASTION_PLUGIN_NAME = "Bastion";
    private static final String GLOBAL_CAPABILITY_SCHEMA_DISPLAY_NAME = "OCI.ComputeGlobalImageCapabilitySchema";

    private final ResourceContext context;
    private final ComputeConfiguration computeConfiguration;
    private final InfrastructureMetadata metadata;
    private final RegionalClient<ComputeClient> computeClient;
    private final RegionalClient<VirtualNetworkClient> vcnClient;
    private final SshFactory sshFactory;
    private final Nix nix;
    private final ComputeConsoleHistoryCollector.Factory consoleHistoryCollectorFactory;

    private final Map<String, NixosImageResource> nixosBootstrapByPlatform = new ConcurrentHashMap<>();

    public Compute(ResourceContext context,
                   ComputeConfiguration computeConfiguration,
                   InfrastructureMetadata metadata,
                   RegionalClient<ComputeClient> computeClient,
                   RegionalClient<VirtualNetworkClient> vcnClient,
                   SshFactory sshFactory, Nix nix, ComputeConsoleHistoryCollector.Factory consoleHistoryCollectorFactory) {
        this.context = context;
        this.computeConfiguration = computeConfiguration;
        this.metadata = metadata;
        this.computeClient = computeClient;
        this.vcnClient = vcnClient;
        this.sshFactory = sshFactory;
        this.nix = nix;
        this.consoleHistoryCollectorFactory = consoleHistoryCollectorFactory;
    }

    private NixosImageResource getNixosBootstrapImage(String platform) {
        return nixosBootstrapByPlatform.computeIfAbsent(platform, k -> {
            NixosImageResource resource = new NixosImageResource(context, k);
            resource.start();
            return resource;
        });
    }

    /**
     * Builder for a new compute instance.
     *
     * @param instanceType The benchmark metadata instance type key to select
     * @param location     The location where to create the instance
     * @param subnet       Subnet for the instance VNIC
     * @return The instance builder
     */
    public Launch builder(String instanceType, OciLocation location, SubnetResource subnet) {
        return new Launch(instanceType, getInstanceType(instanceType), location, subnet);
    }

    /**
     * Get the instance configuration.
     *
     * @param instanceType The instance type config key
     * @return The configuration
     */
    public InstanceType getInstanceType(String instanceType) {
        return metadata.instanceType(instanceType);
    }

    public final class Launch {
        private final InstanceResource resource = new InstanceResource(context, this);
        final ComputeResource computeResource = new ComputeResource(context);
        private final String displayName;
        private final InstanceType instanceType;
        private final OciLocation location;
        private final SubnetResource subnet;
        private OutputListener consoleHistory;
        private ComputeConsoleHistoryCollector consoleHistoryCollector;
        private String privateIp = null;
        private InstanceAccess access;

        private NixosCacheResource nixosConfiguration;

        private final Map<Path, byte[]> systemdCredentials = new HashMap<>();

        private Launch(String displayName, InstanceType instanceType, OciLocation location, SubnetResource subnet) {
            this.displayName = displayName;
            this.instanceType = Objects.requireNonNull(instanceType, "instanceType");
            this.location = location;
            this.subnet = subnet;
            this.computeResource.beforeDelete(() -> {
                if (consoleHistoryCollector != null) {
                    consoleHistoryCollector.captureNow();
                }
            });
            this.computeResource.name(displayName);
            computeResource.dependOn(subnet.require());
            resource.dependOn(computeResource.require());
        }

        /**
         * Set the private IP within the subnet.
         *
         * @param privateIp The IP
         * @return This builder
         */
        public Launch privateIp(String privateIp) {
            this.privateIp = privateIp;
            return this;
        }

        public Launch consoleHistory(OutputListener listener) {
            this.consoleHistory = Objects.requireNonNull(listener, "listener");
            return this;
        }

        public Launch access(InstanceAccess access) {
            resource.dependOn(access.require());
            this.access = access;
            return this;
        }

        public Launch nixosConfiguration(String configurationName) {
            nixosConfiguration = cacheResource(instanceType, configurationName);
            AbstractInfrastructure.launch(nixosConfiguration, nixosConfiguration::manage);
            nixosConfiguration.signalPublication();
            return nixosConfiguration(nixosConfiguration);
        }

        public Launch nixosConfiguration(NixosCacheResource nixosConfiguration) {
            this.nixosConfiguration = Objects.requireNonNull(nixosConfiguration, "nixosConfiguration");
            computeResource.dependOn(nixosConfiguration.requirePublished());
            return this;
        }

        private String platform() {
            return instanceType.platform();
        }

        public Launch systemdCredential(Path path, byte[] value) {
            systemdCredentials.put(path, value);
            return this;
        }

        public InstanceResource resource() {
            return resource;
        }

        /**
         * Create this instance. Note that it is not started immediately, it takes some time.
         *
         * @return The instance
         */
        @Deprecated
        public Instance launch() {
            List<PhasedResource.PhaseLock> locks = resource.require();
            return new Instance(launchAsResource(), locks);
        }

        public InstanceResource launchAsResource() {
            AbstractInfrastructure.launch(resource, resource::manage);
            return resource;
        }

        private void manageBastion(BastionSessionResource sessionResource) throws Exception {
            sessionResource.manageNew(location, CreateSessionDetails.builder()
                    .keyDetails(PublicKeyDetails.builder()
                            .publicKeyContent(sshFactory.publicKey())
                            .build())
                    .keyType(CreateSessionDetails.KeyType.Pub)
                    .sessionTtlInSeconds(Math.toIntExact(Duration.ofHours(3).toSeconds()))
                    .targetResourceDetails(
                            // managed ssh sessions are unstable, so just use port forwarding
                            CreatePortForwardingSessionTargetResourceDetails.builder()
                                    .targetResourcePort(22)
                                    .targetResourcePrivateIpAddress(privateIp)
                                    .build()));
        }
    }

    /**
     * A compute instance.
     */
    public final class Instance implements AutoCloseable {
        private final InstanceResource resource;
        private final List<PhasedResource.PhaseLock> lock;

        private Instance(InstanceResource resource, List<PhasedResource.PhaseLock> lock) {
            this.resource = resource;
            this.lock = lock;
        }

        /**
         * Block while this instance is starting.
         */
        public void awaitStartup() throws Exception {
            PhasedResource.PhaseLock.awaitAll(lock);
        }

        /**
         * Trigger termination of this instance, asynchronously.
         */
        @Deprecated
        public void terminateAsync() {
            close();
        }

        /**
         * Terminate this instance and wait for it to shut down. The caller <i>should</i> call
         * {@link #terminateAsync()} before this.
         */
        @Override
        public synchronized void close() {
            for (PhasedResource.PhaseLock phaseLock : lock) {
                phaseLock.close();
            }
        }

        public InstanceResource resource() {
            return resource;
        }

        public CommandRunner connectSsh() throws Exception {
            return resource.connectSsh();
        }
    }

    NixosCacheResource cacheResource(InstanceType instanceType, String configuration) {
        String platform = instanceType.platform();
        String installable = "./nix#lib.infrastructure." + platform + ".systems." + configuration + "-system";
        return new NixosCacheResource(
                context,
                computeConfiguration.storageBucketNamespace,
                computeConfiguration.storageBucketName,
                "nixos-cache",
                installable
            );
    }

    NixosCacheResource outputCache(Path output) {
        return new NixosCacheResource(context, computeConfiguration.storageBucketNamespace,
                computeConfiguration.storageBucketName, "nixos-cache", output.toString());
    }

    private final class NixosImageResource extends AbstractDecoratedResource {
        private final StorageObjectResource imageResource;
        private final String platform;
        private String id;
        private ComputeImageCapabilitySchemaResource capabilitySchemaResource;
        private List<PhaseLock> capabilitySchemaLocks;
        private boolean capabilitySchemaReady;

        NixosImageResource(ResourceContext context, String platform) {
            super(context);
            this.platform = platform;
            this.imageResource = new StorageObjectResource(
                    context,
                    computeConfiguration.storageBucketNamespace,
                    computeConfiguration.storageBucketName,
                    objectName(platform),
                    upload -> {
                        LOG.info("Building nixos image for {}", platform);
                        try (var log = new OutputListener.Stream(List.of(new OutputListener.Log(LOG, Level.DEBUG)))) {
                            Path path = nix.build(log, "./nix#lib.infrastructure." + platform + ".bootstrapImage").resolve("nixos.qcow2");
                            upload.accept(path);
                        }
                    }
            );
        }

        private static String objectName(String platform) {
            return "nixos/image/" + platform;
        }

        void start() {
            dependOn(imageResource.require());
            AbstractInfrastructure.launch(imageResource, imageResource::manage);
            AbstractInfrastructure.launch(this, this::manage);
        }

        public String getId() {
            if (id == null) {
                throw new IllegalStateException("Not yet ready");
            }
            return id;
        }

        @Override
        protected void setUp() throws Exception {
            String os = "nixos-" + platform;
            String version = imageResource.getHash();

            OciLocation imageLocation = new OciLocation(computeConfiguration.storageBucketCompartment, Region.EU_FRANKFURT_1.getRegionId(), null);
            Image match = null;
            for (Image image : CustomImageResource.list(context, imageLocation)) {
                if (image.getOperatingSystem().equals(os)) {
                    if (image.getOperatingSystemVersion().equals(version)
                            && image.getLaunchMode() == Image.LaunchMode.Paravirtualized) {
                        match = image;
                    } else {
                        LOG.info("Deleting stale nixos image {} ({})", image.getDisplayName(), image.getId());
                        deleteCapabilitySchemas(imageLocation, image.getId());
                        computeClient.forRegion(imageLocation).deleteImage(DeleteImageRequest.builder()
                                .imageId(image.getId())
                                .build());
                    }
                }
            }
            CustomImageResource imageResource = new CustomImageResource(context);

            if (match == null) {
                AbstractInfrastructure.launch(imageResource, () -> imageResource.manageNew(imageLocation, CreateImageDetails.builder()
                        .compartmentId(imageLocation.compartmentId())
                        .displayName(os + "-" + version)
                        .imageSourceDetails(ImageSourceViaObjectStorageTupleDetails.builder()
                                .namespaceName(computeConfiguration.storageBucketNamespace)
                                .bucketName(computeConfiguration.storageBucketName)
                                .objectName(objectName(platform))
                                .operatingSystem(os)
                                .operatingSystemVersion(version)
                                .sourceImageType(ImageSourceDetails.SourceImageType.Qcow2)
                                .build())
                        .launchMode(CreateImageDetails.LaunchMode.Paravirtualized)));
            } else {
                String existingImageId = match.getId();
                AbstractInfrastructure.launch(imageResource, () -> imageResource.manageExisting(imageLocation, existingImageId));
            }

            PhaseLock.awaitAll(imageResource.require());
            String imageId = imageResource.ocid();
            reconcileCapabilitySchema(imageLocation, imageId);
            id = imageId;
            capabilitySchemaReady = true;
        }

        private void reconcileCapabilitySchema(OciLocation location, String imageId) throws Exception {
            String globalSchemaVersion = currentGlobalSchemaVersion(computeClient.forRegion(location));
            List<ComputeImageCapabilitySchema> schemas = ComputeImageCapabilitySchemaResource.list(context, location, imageId).stream()
                    .filter(schema -> schema.getLifecycleState() != ComputeImageCapabilitySchema.LifecycleState.Deleted)
                    .toList();
            if (schemas.size() == 1 && isDesiredCapabilitySchema(schemas.getFirst(), globalSchemaVersion)) {
                capabilitySchemaResource = new ComputeImageCapabilitySchemaResource(context);
                capabilitySchemaLocks = capabilitySchemaResource.require();
                AbstractInfrastructure.launch(capabilitySchemaResource,
                        () -> capabilitySchemaResource.manageExisting(location, schemas.getFirst().getId()));
                PhaseLock.awaitAll(capabilitySchemaLocks);
                return;
            }

            deleteCapabilitySchemas(location, schemas);
            capabilitySchemaResource = new ComputeImageCapabilitySchemaResource(context);
            capabilitySchemaLocks = capabilitySchemaResource.require();
            AbstractInfrastructure.launch(capabilitySchemaResource, () -> capabilitySchemaResource.manageNew(location,
                    CreateComputeImageCapabilitySchemaDetails.builder()
                            .imageId(imageId)
                            .displayName("nixos-" + platform)
                             .computeGlobalImageCapabilitySchemaVersionName(globalSchemaVersion)
                             .schemaData(desiredCapabilitySchemaData())));
            PhaseLock.awaitAll(capabilitySchemaLocks);
        }

        private String currentGlobalSchemaVersion(ComputeClient client) {
            List<ComputeGlobalImageCapabilitySchemaSummary> schemas = client.listComputeGlobalImageCapabilitySchemas(
                    ListComputeGlobalImageCapabilitySchemasRequest.builder()
                            .displayName(GLOBAL_CAPABILITY_SCHEMA_DISPLAY_NAME)
                            .build())
                    .getItems();
            if (schemas.size() != 1) {
                throw new IllegalStateException("Expected exactly one global image capability schema named "
                        + GLOBAL_CAPABILITY_SCHEMA_DISPLAY_NAME + ", found " + schemas.size());
            }
            return schemas.getFirst().getCurrentVersionName();
        }

        private void deleteCapabilitySchemas(OciLocation location, String imageId) throws Exception {
            deleteCapabilitySchemas(location, ComputeImageCapabilitySchemaResource.list(context, location, imageId));
        }

        private void deleteCapabilitySchemas(OciLocation location, List<ComputeImageCapabilitySchema> schemas) throws Exception {
            for (ComputeImageCapabilitySchema schema : schemas) {
                if (schema.getLifecycleState() != ComputeImageCapabilitySchema.LifecycleState.Deleted) {
                    new ComputeImageCapabilitySchemaResource(context).manageExisting(location, schema.getId());
                }
            }
        }

        private static boolean isDesiredCapabilitySchema(ComputeImageCapabilitySchema schema, String globalSchemaVersion) {
            return schema.getLifecycleState() == ComputeImageCapabilitySchema.LifecycleState.Active
                    && globalSchemaVersion.equals(schema.getComputeGlobalImageCapabilitySchemaVersionName())
                    && hasDesiredCapabilitySchemaData(schema.getSchemaData());
        }

        private static boolean hasDesiredCapabilitySchemaData(Map<String, ImageCapabilitySchemaDescriptor> schemaData) {
            Map<String, ImageCapabilitySchemaDescriptor> desired = desiredCapabilitySchemaData();
            return desired.entrySet().stream().allMatch(entry -> entry.getValue().equals(schemaData.get(entry.getKey())));
        }

        private static Map<String, ImageCapabilitySchemaDescriptor> desiredCapabilitySchemaData() {
            ImageCapabilitySchemaDescriptor.Source source = ImageCapabilitySchemaDescriptor.Source.Image;
            return Map.of(
                    "Compute.Firmware", enumDescriptor(source, List.of("UEFI_64"), "UEFI_64"),
                    "Compute.LaunchMode", enumDescriptor(source, List.of("PARAVIRTUALIZED"), "PARAVIRTUALIZED"),
                    "Network.AttachmentType", enumDescriptor(source, List.of("VFIO", "PARAVIRTUALIZED"), "VFIO"),
                    "Storage.BootVolumeType", enumDescriptor(source, List.of("PARAVIRTUALIZED"), "PARAVIRTUALIZED"),
                    "Storage.LocalDataVolumeType", enumDescriptor(source, List.of("PARAVIRTUALIZED"), "PARAVIRTUALIZED"),
                    "Storage.RemoteDataVolumeType", enumDescriptor(source, List.of("PARAVIRTUALIZED"), "PARAVIRTUALIZED"),
                    "Storage.ConsistentVolumeNaming", BooleanImageCapabilitySchemaDescriptor.builder().source(source).defaultValue(true).build(),
                    "Storage.ParaVirtualization.EncryptionInTransit", BooleanImageCapabilitySchemaDescriptor.builder().source(source).defaultValue(true).build()
            );
        }

        private static EnumStringImageCapabilitySchemaDescriptor enumDescriptor(
                ImageCapabilitySchemaDescriptor.Source source,
                List<String> values,
                String defaultValue) {
            return EnumStringImageCapabilitySchemaDescriptor.builder()
                    .source(source)
                    .values(values)
                    .defaultValue(defaultValue)
                    .build();
        }

        @Override
        protected void unlock() {
            if (!capabilitySchemaReady && capabilitySchemaLocks != null) {
                for (PhaseLock capabilitySchemaLock : capabilitySchemaLocks) {
                    capabilitySchemaLock.close();
                }
            }
        }
    }

    public final class InstanceResource extends AbstractDecoratedResource {
        private final Launch launch;
        private String publicIp;

        InstanceResource(ResourceContext context, Launch launch) {
            super(context);
            this.launch = launch;
        }

        @SuppressWarnings("StringConcatenationInLoop")
        @Override
        protected void launchDependencies() throws Exception {
            NixosImageResource imageResource = getNixosBootstrapImage(launch.platform());
            launch.computeResource.dependOn(imageResource.require());

            AbstractInfrastructure.launch(launch.computeResource, () -> launch.computeResource.manageNew(launch.location, () -> {
                CreateVnicDetails.Builder vnicDetails = CreateVnicDetails.builder()
                        .subnetId(launch.subnet.ocid())
                        .assignPublicIp(launch.access instanceof PublicIpAccess);
                if (launch.privateIp != null) {
                    vnicDetails.privateIp(launch.privateIp);
                }

                String userDataScript = "#!/bin/sh\nset -e\n";
                for (Map.Entry<Path, byte[]> entry : launch.systemdCredentials.entrySet()) {
                    userDataScript += "mkdir -p " + entry.getKey().getParent() + "\n";
                    userDataScript += "touch " + entry.getKey() + "\n";
                    userDataScript += "chmod 600 " + entry.getKey() + "\n";
                    userDataScript += "echo '" + Base64.getEncoder().encodeToString(entry.getValue()) + "' | base64 -d > " + entry.getKey() + "\n";
                }
                if (launch.nixosConfiguration != null) {
                    NixCacheAccess cache = launch.nixosConfiguration.cacheAccess();
                    userDataScript += Nix.activate(cache.readUri(), cache.defaultOutput());
                }
                userDataScript += "systemctl start benchmark-role-ready.target\n";

                return LaunchInstanceDetails.builder()
                        .sourceDetails(InstanceSourceViaImageDetails.builder()
                                .imageId(imageResource.getId())
                                .bootVolumeVpusPerGB(launch.instanceType.diskPerformanceUnits().longValue())
                                .build())
                        .displayName(launch.displayName)
                        .shape(launch.instanceType.shape())
                        .shapeConfig(LaunchInstanceShapeConfigDetails.builder()
                                .ocpus(launch.instanceType.ocpus())
                                .memoryInGBs(launch.instanceType.memoryInGb())
                                .build())
                        .createVnicDetails(vnicDetails.build())
                        .imageId(imageResource.getId())
                        .metadata(Map.of(
                                "ssh_authorized_keys", authorizedKeys().collect(Collectors.joining("\n")),
                                "user_data", Base64.getEncoder().encodeToString(userDataScript.getBytes(StandardCharsets.UTF_8))))
                        .launchOptions(LaunchOptions.builder()
                                .firmware(LaunchOptions.Firmware.Uefi64)
                                .networkType(LaunchOptions.NetworkType.Vfio)
                                .bootVolumeType(LaunchOptions.BootVolumeType.Paravirtualized)
                                .remoteDataVolumeType(LaunchOptions.RemoteDataVolumeType.Paravirtualized)
                                .isConsistentVolumeNamingEnabled(true)
                                .build())
                        .isPvEncryptionInTransitEnabled(true)
                        .instanceOptions(InstanceOptions.builder()
                                .areLegacyImdsEndpointsDisabled(true)
                                .build())
                        .agentConfig(LaunchInstanceAgentConfigDetails.builder()
                                .pluginsConfig(List.of(
                                        InstanceAgentPluginConfigDetails.builder()
                                                .name(BASTION_PLUGIN_NAME)
                                                .desiredState(InstanceAgentPluginConfigDetails.DesiredState.Enabled)
                                                .build()
                                ))
                                .build());
            }, instance -> {
                if (launch.consoleHistory != null) {
                    launch.consoleHistoryCollector = consoleHistoryCollectorFactory.create(
                            launch.location, instance.getId(), instance.getDisplayName(), launch.consoleHistory);
                }
            }));

            launch.access.launch(launch);
        }

        @Override
        protected void setUp() throws Exception {
            if (launch.access instanceof PublicIpAccess) {
                this.publicIp = Infrastructure.retry(() -> {
                    String vnic = computeClient.forRegion(launch.location).listVnicAttachments(ListVnicAttachmentsRequest.builder()
                            .compartmentId(launch.location.compartmentId())
                            .availabilityDomain(launch.location.availabilityDomain())
                            .instanceId(launch.computeResource.ocid())
                            .build()).getItems().getFirst().getVnicId();
                    return vcnClient.forRegion(launch.location).getVnic(GetVnicRequest.builder()
                            .vnicId(vnic)
                            .build()).getVnic().getPublicIp();
                });
            }
        }

        private Stream<String> authorizedKeys() {
            return Stream.concat(computeConfiguration.debugAuthorizedKeys.stream(), Stream.of(sshFactory.publicKey()));
        }

        public CommandRunner connectSsh() throws Exception {
            switch (launch.access) {
                case BastionAccess bastionAccess -> {
                    SshFactory.Relay relay = new SshFactory.Relay(bastionAccess.sessionResource.getBastionUserName(), "host.bastion." + launch.location.region() + ".oci.oraclecloud.com");
                    return Infrastructure.retry(() -> sshFactory.connect(this, launch.privateIp, relay));
                }
                case HttpRelayAccess httpRelayAccess -> {
                    return httpRelayAccess.relay.getRelay().openSession("root@" + launch.privateIp + ":22");
                }
                case PublicIpAccess _ -> {
                    return Infrastructure.retry(() -> sshFactory.connect(this, publicIp, null));
                }
                case SshRelayAccess sshRelayAccess -> {
                    SshFactory.Relay relay = new SshFactory.Relay("root", sshRelayAccess.relayInstance.publicIp);
                    return Infrastructure.retry(() -> sshFactory.connect(this, launch.privateIp, relay));
                }
            }
        }

        public String publicIp() {
            return publicIp;
        }

        @Override
        public String toString() {
            return "InstanceResource[" + launch.computeResource + "]";
        }

        public void awaitTermination() throws InterruptedException {
            awaitPhase(Phase.Terminated);
        }
    }

    /**
     * @param debugAuthorizedKeys Additional SSH keys to add to each instance for debugging
     */
    @ConfigurationProperties("compute")
    public record ComputeConfiguration(
            List<String> debugAuthorizedKeys,
            String storageBucketCompartment,
            String storageBucketNamespace,
            String storageBucketName
    ) {

    }

    public sealed interface InstanceAccess {
        default void launch(Launch launch) {
        }

        List<PhasedResource.PhaseLock> require();
    }

    public record BastionAccess(BastionSessionResource sessionResource) implements InstanceAccess {
        @Override
        public void launch(Launch launch) {
            AbstractInfrastructure.launch(sessionResource, () -> launch.manageBastion(sessionResource));
        }

        @Override
        public List<PhasedResource.PhaseLock> require() {
            return sessionResource.require();
        }
    }

    public record SshRelayAccess(InstanceResource relayInstance) implements InstanceAccess {
        @Override
        public List<PhasedResource.PhaseLock> require() {
            return relayInstance.require();
        }
    }

    public record HttpRelayAccess(TcpAgentRelay.TcpRelayResource relay) implements InstanceAccess {
        @Override
        public List<PhasedResource.PhaseLock> require() {
            return relay.require();
        }
    }

    public record PublicIpAccess() implements InstanceAccess {
        @Override
        public List<PhasedResource.PhaseLock> require() {
            return List.of();
        }
    }
}
