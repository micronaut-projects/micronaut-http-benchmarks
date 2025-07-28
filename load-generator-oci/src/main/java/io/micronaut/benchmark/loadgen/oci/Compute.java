package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.bastion.BastionClient;
import com.oracle.bmc.bastion.model.CreatePortForwardingSessionTargetResourceDetails;
import com.oracle.bmc.bastion.model.CreateSessionDetails;
import com.oracle.bmc.bastion.model.PublicKeyDetails;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.CreateVnicDetails;
import com.oracle.bmc.core.model.Image;
import com.oracle.bmc.core.model.InstanceAgentPluginConfigDetails;
import com.oracle.bmc.core.model.LaunchInstanceAgentConfigDetails;
import com.oracle.bmc.core.model.LaunchInstanceDetails;
import com.oracle.bmc.core.model.LaunchInstanceShapeConfigDetails;
import com.oracle.bmc.core.model.LaunchOptions;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.core.requests.ListImagesRequest;
import com.oracle.bmc.core.requests.ListVnicAttachmentsRequest;
import io.micronaut.benchmark.loadgen.oci.resource.AbstractDecoratedResource;
import io.micronaut.benchmark.loadgen.oci.resource.BastionResource;
import io.micronaut.benchmark.loadgen.oci.resource.BastionSessionResource;
import io.micronaut.benchmark.loadgen.oci.resource.ComputeResource;
import io.micronaut.benchmark.loadgen.oci.resource.PhasedResource;
import io.micronaut.benchmark.loadgen.oci.resource.ResourceContext;
import io.micronaut.benchmark.loadgen.oci.resource.SubnetResource;
import io.micronaut.benchmark.relay.CommandRunner;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class handles provisioning of compute instances (VMs) according to configured instance settings.
 */
@Singleton
public final class Compute {
    private static final Logger LOG = LoggerFactory.getLogger(Compute.class);
    private static final String BASTION_PLUGIN_NAME = "Bastion";

    private final ResourceContext context;
    private final ComputeConfiguration computeConfiguration;
    private final Map<String, ComputeConfiguration.InstanceType> instanceTypes;
    private final RegionalClient<ComputeClient> computeClient;
    private final RegionalClient<VirtualNetworkClient> vcnClient;
    private final RegionalClient<BastionClient> bastionClient;
    private final SshFactory sshFactory;
    private final Executor blocking;

    private final Map<OciLocation, List<Image>> imagesByCompartment = new ConcurrentHashMap<>();

    public Compute(ResourceContext context,
                   ComputeConfiguration computeConfiguration,
                   Map<String, ComputeConfiguration.InstanceType> instanceTypes,
                   RegionalClient<ComputeClient> computeClient,
                   RegionalClient<VirtualNetworkClient> vcnClient,
                   RegionalClient<BastionClient> bastionClient,
                   SshFactory sshFactory,
                   @Named(TaskExecutors.BLOCKING) Executor blocking) {
        this.context = context;
        this.computeConfiguration = computeConfiguration;
        this.instanceTypes = instanceTypes;
        this.computeClient = computeClient;
        this.vcnClient = vcnClient;
        this.bastionClient = bastionClient;
        this.sshFactory = sshFactory;
        this.blocking = blocking;
    }

    private List<Image> images(OciLocation location) {
        return imagesByCompartment.computeIfAbsent(location, k -> computeClient.forRegion(k).listImages(ListImagesRequest.builder()
                .compartmentId(k.compartmentId())
                .build()).getItems());
    }

    /**
     * Builder for a new compute instance.
     *
     * @param instanceType The instance type. This is used as key for the {@link ComputeConfiguration.InstanceType}
     *                     config to select
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
    public ComputeConfiguration.InstanceType getInstanceType(String instanceType) {
        return instanceTypes.get(instanceType);
    }

    public final class Launch {
        private final InstanceResource resource = new InstanceResource(context, this);
        private final ComputeResource computeResource = new ComputeResource(context);
        private final String displayName;
        private final ComputeConfiguration.InstanceType instanceType;
        private final OciLocation location;
        private final SubnetResource subnet;
        private String privateIp = null;
        private boolean publicIp = false;
        private BastionSessionResource bastionSession = null;
        private InstanceResource relayInstance = null;

        private Launch(String displayName, ComputeConfiguration.InstanceType instanceType, OciLocation location, SubnetResource subnet) {
            this.displayName = displayName;
            this.instanceType = Objects.requireNonNull(instanceType, "instanceType");
            this.location = location;
            this.subnet = subnet;
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

        /**
         * Assign a public IP to this server.
         *
         * @param publicIp Whether to assign a public IP
         * @return This builder
         */
        public Launch publicIp(boolean publicIp) {
            this.publicIp = publicIp;
            return this;
        }

        public Launch bastion(BastionResource bastion) {
            if (bastion != null) {
                this.bastionSession = new BastionSessionResource(context).bastion(bastion);
                bastionSession.dependOn(computeResource.require());
                resource.dependOn(bastionSession.require());
            }
            return this;
        }

        public Launch relayInstance(InstanceResource relayInstance) {
            if (relayInstance != null) {
                this.relayInstance = relayInstance;
                resource.dependOn(relayInstance.require());
            }
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

        public AbstractDecoratedResource resource() {
            return resource;
        }

        public CommandRunner connectSsh() throws Exception {
            return resource.connectSsh();
        }
    }

    public final class InstanceResource extends AbstractDecoratedResource {
        private final Launch launch;
        private String publicIp;
        private SshFactory.Relay relay;

        InstanceResource(ResourceContext context, Launch launch) {
            super(context);
            this.launch = launch;
        }

        @Override
        protected void launchDependencies() throws Exception {
            List<Image> images = images(launch.location);
            Image image = images.stream()
                    .filter(i -> i.getId().equals(launch.instanceType.image) || i.getDisplayName().equals(launch.instanceType.image))
                    .findAny()
                    .orElseThrow(() -> new NoSuchElementException("Image " + launch.instanceType.image + " not found. Available images are: \n" + images.stream().map(Image::getDisplayName).collect(Collectors.joining("\n"))));

            AbstractInfrastructure.launch(launch.computeResource, () -> launch.computeResource.manageNew(launch.location, () -> {
                CreateVnicDetails.Builder vnicDetails = CreateVnicDetails.builder()
                        .subnetId(launch.subnet.ocid())
                        .assignPublicIp(launch.publicIp);
                if (launch.privateIp != null) {
                    vnicDetails.privateIp(launch.privateIp);
                }
                return LaunchInstanceDetails.builder()
                        .displayName(launch.displayName)
                        .shape(launch.instanceType.shape)
                        .shapeConfig(LaunchInstanceShapeConfigDetails.builder()
                                .ocpus(launch.instanceType.ocpus)
                                .memoryInGBs(launch.instanceType.memoryInGb)
                                .build())
                        .createVnicDetails(vnicDetails.build())
                        .imageId(image.getId())
                        .metadata(Map.of(
                                "ssh_authorized_keys",
                                Stream.concat(computeConfiguration.debugAuthorizedKeys.stream(), Stream.of(sshFactory.publicKey()))
                                        .collect(Collectors.joining("\n"))
                        ))
                        .launchOptions(LaunchOptions.builder()
                                .networkType(LaunchOptions.NetworkType.Vfio)
                                .build())
                        .agentConfig(LaunchInstanceAgentConfigDetails.builder()
                                .pluginsConfig(List.of(
                                        InstanceAgentPluginConfigDetails.builder()
                                                .name(BASTION_PLUGIN_NAME)
                                                .desiredState(InstanceAgentPluginConfigDetails.DesiredState.Enabled)
                                                .build()
                                ))
                                .build());
            }));

            if (launch.bastionSession != null) {
                AbstractInfrastructure.launch(launch.bastionSession, () -> {
                    launch.bastionSession.manageNew(launch.location, CreateSessionDetails.builder()
                            .keyDetails(PublicKeyDetails.builder()
                                    .publicKeyContent(sshFactory.publicKey())
                                    .build())
                            .keyType(CreateSessionDetails.KeyType.Pub)
                            .sessionTtlInSeconds(Math.toIntExact(Duration.ofHours(3).toSeconds()))
                            .targetResourceDetails(
                                    // managed ssh sessions are unstable, so just use port forwarding
                                    CreatePortForwardingSessionTargetResourceDetails.builder()
                                            .targetResourcePort(22)
                                            .targetResourcePrivateIpAddress(launch.privateIp)
                                            .build()));
                });
            }
        }

        @Override
        protected void setUp() {
            if (launch.publicIp) {
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
            if (launch.bastionSession != null) {
                this.relay = new SshFactory.Relay(launch.bastionSession.getBastionUserName(), "host.bastion." + launch.location.region() + ".oci.oraclecloud.com");
            } else if (launch.relayInstance != null) {
                this.relay = new SshFactory.Relay("opc", launch.relayInstance.publicIp);
            }
        }

        public CommandRunner connectSsh() throws Exception {
            if (publicIp != null) {
                return sshFactory.connect(this, publicIp, null);
            } else {
                return Infrastructure.retry(() -> sshFactory.connect(this, launch.privateIp, relay));
            }
        }

        @Override
        public String toString() {
            return "InstanceResource[" + launch.computeResource + "]";
        }
    }

    /**
     * @param instanceTypes       Instance types
     * @param debugAuthorizedKeys Additional SSH keys to add to each instance for debugging
     */
    @ConfigurationProperties("compute")
    public record ComputeConfiguration(
            List<InstanceType> instanceTypes,
            List<String> debugAuthorizedKeys
    ) {

        /**
         * An instance type.
         *
         * @param shape OCI shape
         * @param ocpus Number of cores
         * @param memoryInGb Memory in GB
         * @param image OS image name
         */
        @EachProperty("instance-types")
        public record InstanceType(
                String shape,
                float ocpus,
                float memoryInGb,
                String image
        ) {
        }
    }
}
