package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.model.CreateVnicDetails;
import com.oracle.bmc.core.model.Image;
import com.oracle.bmc.core.model.LaunchInstanceDetails;
import com.oracle.bmc.core.model.LaunchInstanceShapeConfigDetails;
import com.oracle.bmc.core.model.LaunchOptions;
import com.oracle.bmc.core.requests.GetInstanceRequest;
import com.oracle.bmc.core.requests.LaunchInstanceRequest;
import com.oracle.bmc.core.requests.ListImagesRequest;
import com.oracle.bmc.core.requests.TerminateInstanceRequest;
import com.oracle.bmc.model.BmcException;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.EachProperty;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class handles provisioning of compute instances (VMs) according to configured instance settings.
 */
@Singleton
public final class Compute {
    private static final Logger LOG = LoggerFactory.getLogger(Compute.class);

    private final ComputeConfiguration computeConfiguration;
    private final Map<String, ComputeConfiguration.InstanceType> instanceTypes;
    private final RegionalClient<ComputeClient> computeClient;
    private final SshFactory sshFactory;

    private final Map<OciLocation, List<Image>> imagesByCompartment = new ConcurrentHashMap<>();

    public Compute(ComputeConfiguration computeConfiguration, Map<String, ComputeConfiguration.InstanceType> instanceTypes, RegionalClient<ComputeClient> computeClient, SshFactory sshFactory) {
        this.computeConfiguration = computeConfiguration;
        this.instanceTypes = instanceTypes;
        this.computeClient = computeClient;
        this.sshFactory = sshFactory;
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
     * @param subnetId     Subnet for the instance VNIC
     * @return The instance builder
     */
    public Launch builder(String instanceType, OciLocation location, String subnetId) {
        return new Launch(instanceType, getInstanceType(instanceType), location, subnetId);
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
        private final String displayName;
        private final ComputeConfiguration.InstanceType instanceType;
        private final OciLocation location;
        private final String subnetId;
        private String privateIp = null;
        private boolean publicIp = false;

        private Launch(String displayName, ComputeConfiguration.InstanceType instanceType, OciLocation location, String subnetId) {
            this.displayName = displayName;
            this.instanceType = Objects.requireNonNull(instanceType, "instanceType");
            this.location = location;
            this.subnetId = subnetId;
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

        /**
         * Create this instance. Note that it is not started immediately, it takes some time.
         *
         * @return The instance
         */
        public Instance launch() throws InterruptedException {
            CreateVnicDetails.Builder vnicDetails = CreateVnicDetails.builder()
                    .subnetId(subnetId)
                    .assignPublicIp(publicIp);
            if (privateIp != null) {
                vnicDetails.privateIp(privateIp);
            }
            Throttle.COMPUTE.takeUninterruptibly();
            List<Image> images = images(location);
            Image image = images.stream()
                    .filter(i -> i.getId().equals(instanceType.image) || i.getDisplayName().equals(instanceType.image))
                    .findAny()
                    .orElseThrow(() -> new NoSuchElementException("Image " + instanceType.image + " not found. Available images are: \n" + images.stream().map(Image::getDisplayName).collect(Collectors.joining("\n"))));
            while (true) {
                try {
                    String id = computeClient.forRegion(location).launchInstance(LaunchInstanceRequest.builder()
                            .launchInstanceDetails(LaunchInstanceDetails.builder()
                                    .compartmentId(location.compartmentId())
                                    .availabilityDomain(location.availabilityDomain())
                                    .displayName(displayName)
                                    .shape(instanceType.shape)
                                    .shapeConfig(LaunchInstanceShapeConfigDetails.builder()
                                            .ocpus(instanceType.ocpus)
                                            .memoryInGBs(instanceType.memoryInGb)
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
                                    .build())
                            .build()).getInstance().getId();
                    return new Instance(location, id);
                } catch (BmcException bmce) {
                    if (bmce.getStatusCode() == 429) {
                        LOG.info("429 while launching instance! Waiting 30s.");
                        TimeUnit.SECONDS.sleep(30);
                        continue;
                    }
                    throw bmce;
                }
            }
        }
    }

    /**
     * A compute instance.
     */
    public final class Instance implements AutoCloseable {
        private final OciLocation location;
        final String id;

        private boolean terminating = false;

        Instance(OciLocation location, String id) {
            this.location = location;
            this.id = id;
        }

        /**
         * Check that the given instance is provisioning or started.
         *
         * @return {@code true} if the instance is running, {@code false} if it is still being set up
         * @throws IllegalStateException if the instance is shutting down
         */
        public boolean checkStarted() {
            Throttle.COMPUTE.takeUninterruptibly();
            com.oracle.bmc.core.model.Instance.LifecycleState lifecycleState = getLifecycleState();
            switch (lifecycleState) {
                case Running -> {
                    return true;
                }
                case Provisioning, Starting ->
                        LOG.info("Waiting for instance {} to start ({})...", id, lifecycleState);
                default -> throw new IllegalStateException("Unexpected lifecycle state: " + lifecycleState);
            }
            return false;
        }

        private com.oracle.bmc.core.model.Instance.LifecycleState getLifecycleState() {
            try {
                return Infrastructure.retry(() -> computeClient.forRegion(location).getInstance(
                                GetInstanceRequest.builder()
                                        .instanceId(id)
                                        .build())
                        .getInstance()
                        .getLifecycleState());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Block while this instance is starting.
         */
        public void awaitStartup() throws InterruptedException {
            while (true) {
                if (checkStarted()) {
                    return;
                }
                TimeUnit.SECONDS.sleep(5);
            }
        }

        /**
         * Trigger termination of this instance, asynchronously.
         */
        public synchronized void terminateAsync() {
            LOG.info("Terminating compute instance {}", id);
            while (true) {
                Throttle.COMPUTE.takeUninterruptibly();
                try {
                    computeClient.forRegion(location).terminateInstance(TerminateInstanceRequest.builder()
                            .instanceId(id)
                            .preserveBootVolume(false)
                            .build());
                    break;
                } catch (BmcException bmce) {
                    if (bmce.getStatusCode() == 429) {
                        LOG.info("429 while terminating compute instance {}, retrying in 30s", id);
                        try {
                            TimeUnit.SECONDS.sleep(30);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        continue;
                    }
                    throw bmce;
                }
            }
            terminating = true;
        }

        /**
         * Terminate this instance and wait for it to shut down. The caller <i>should</i> call
         * {@link #terminateAsync()} before this.
         */
        @Override
        public synchronized void close() {
            if (!terminating) {
                LOG.warn("Instance was not terminated before close() call");
                terminateAsync();
            }

            while (true) {
                Throttle.COMPUTE.takeUninterruptibly();
                com.oracle.bmc.core.model.Instance.LifecycleState lifecycleState = getLifecycleState();
                switch (lifecycleState) {
                    case Terminating -> {}
                    case Terminated -> {
                        return;
                    }
                    default -> throw new IllegalStateException("Unexpected state for compute instance " + id + ": " + lifecycleState + ". Instance should be terminating.");
                }
                LOG.info("Waiting for {} to terminate", id);
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
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
