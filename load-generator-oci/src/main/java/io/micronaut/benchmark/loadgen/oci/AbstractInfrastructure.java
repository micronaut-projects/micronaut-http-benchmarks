package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.bastion.model.CreateBastionDetails;
import com.oracle.bmc.core.model.CreateInternetGatewayDetails;
import com.oracle.bmc.core.model.CreateNatGatewayDetails;
import com.oracle.bmc.core.model.CreateRouteTableDetails;
import com.oracle.bmc.core.model.CreateSubnetDetails;
import com.oracle.bmc.core.model.CreateVcnDetails;
import com.oracle.bmc.core.model.IngressSecurityRule;
import com.oracle.bmc.core.model.PortRange;
import com.oracle.bmc.core.model.RouteRule;
import com.oracle.bmc.core.model.SecurityList;
import com.oracle.bmc.core.model.TcpOptions;
import com.oracle.bmc.core.model.UpdateSecurityListDetails;
import com.oracle.bmc.core.requests.GetSecurityListRequest;
import com.oracle.bmc.core.requests.UpdateSecurityListRequest;
import io.micronaut.benchmark.loadgen.oci.resource.BastionResource;
import io.micronaut.benchmark.loadgen.oci.resource.BastionSessionResource;
import io.micronaut.benchmark.loadgen.oci.resource.InternetGatewayResource;
import io.micronaut.benchmark.loadgen.oci.resource.NatGatewayResource;
import io.micronaut.benchmark.loadgen.oci.resource.PhasedResource;
import io.micronaut.benchmark.loadgen.oci.resource.ResourceContext;
import io.micronaut.benchmark.loadgen.oci.resource.RouteTableResource;
import io.micronaut.benchmark.loadgen.oci.resource.SubnetResource;
import io.micronaut.benchmark.loadgen.oci.resource.VcnResource;
import io.micronaut.core.util.functional.ThrowingSupplier;
import io.netty.util.internal.PlatformDependent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Base class for a set of OCI resources that can be used to run benchmarks. This abstract class is responsible for the
 * VCN, subnets, and the SSH relay server.
 * <p>
 * The basic idea is that we have a group of benchmark-related servers (controllers, servers-under-test, benchmarking
 * agents) running in a private, protected subnet. There is also an SSH relay in a separate public subnet in the same
 * VCN. The SSH relay is used as a jump host to connect to the other servers.
 */
public abstract class AbstractInfrastructure implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractInfrastructure.class);

    /**
     * Full VCN CIDR.
     */
    private static final String NETWORK = "10.0.0.0/16";
    /**
     * Private subnet CIDR, for all servers except the SSH relay.
     *
     * @see #privateSubnet
     */
    private static final String PRIVATE_SUBNET = "10.0.0.0/18";
    /**
     * Public subnet CIDR, only the SSH relay lives here.
     *
     * @see #publicSubnet
     */
    private static final String RELAY_SUBNET = "10.0.254.0/24";

    private static final SshRelayMode RELAY_MODE = SshRelayMode.TCP_AGENT;

    /**
     * Location (compartment, region, AD) of this infrastructure.
     */
    protected final OciLocation location;
    /**
     * Infrastructure log directory.
     */
    protected final Path logDirectory;
    final ResourceContext context;
    private final Compute compute;

    private final SubnetResource publicSubnet;
    /**
     * Subnet for all benchmark-related servers. Not internet-accessible, all transfers must happen through the SSH
     * relay.
     */
    private final SubnetResource privateSubnet;
    private final RouteTableResource privateRouteTable;
    private final RouteTableResource publicRouteTable;
    private final VcnResource vcn;
    private final NatGatewayResource nat;
    private final InternetGatewayResource internet;

    private final Compute.Launch relayServerBuilder;
    private final BastionResource bastion;
    private final TcpAgentRelay.TcpRelayResource tcpRelayResource;

    final List<PhasedResource.PhaseLock> lifecycleLocks = new ArrayList<>();

    protected AbstractInfrastructure(Factory factory, OciLocation location, Path logDirectory) {
        this.location = location;
        this.logDirectory = logDirectory;
        this.context = factory.resourceContext;

        vcn = new VcnResource(context);
        privateRouteTable = new RouteTableResource(context).vcn(vcn);
        privateSubnet = new SubnetResource(context).vcn(vcn).routeTable(privateRouteTable);
        lifecycleLocks.addAll(privateSubnet.require());
        this.compute = factory.compute;
        if (RELAY_MODE == SshRelayMode.RELAY_SERVER || RELAY_MODE == SshRelayMode.TCP_AGENT) {
            internet = new InternetGatewayResource(context).vcn(vcn);
            publicRouteTable = new RouteTableResource(context).vcn(vcn);
            publicRouteTable.dependOn(internet.require());
            publicSubnet = new SubnetResource(context).vcn(vcn).routeTable(publicRouteTable);
            relayServerBuilder = compute.builder("relay-server", location, publicSubnet).access(new Compute.PublicIpAccess());
            lifecycleLocks.addAll(relayServerBuilder.resource().require());
            if (RELAY_MODE == SshRelayMode.TCP_AGENT) {
                try {
                    tcpRelayResource = factory.agentRelayFactory.builder()
                            .log(logDirectory.resolve("http-agent.log"))
                            .sshKeyPair(factory.sshFactory.keyPair())
                            .asResource(context, relayServerBuilder.resource());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                tcpRelayResource = null;
            }
        } else {
            internet = null;
            publicRouteTable = null;
            publicSubnet = null;
            relayServerBuilder = null;
            tcpRelayResource = null;
        }
        if (RELAY_MODE == SshRelayMode.BASTION) {
            bastion = new BastionResource(context).subnet(privateSubnet);
            lifecycleLocks.addAll(bastion.require());
        } else {
            bastion = null;
        }
        nat = new NatGatewayResource(context).vcn(vcn);
        privateRouteTable.dependOn(nat.require());
    }

    static void launch(PhasedResource<?> resource, ThrowingRunnable manage) {
        LOG.info("Launching {}", resource);
        Thread.ofVirtual()
                .name("manage-" + resource)
                .start(() -> {
                    try {
                        manage.run();
                    } catch (Exception e) {
                        LOG.warn("Failed to run resource {}", resource, e);
                    }
                });
    }

    protected final void setupBase(PhaseTracker.PhaseUpdater progress) throws Exception {
        try {
            Files.createDirectories(logDirectory);
        } catch (FileAlreadyExistsException ignored) {
        }

        progress.update(BenchmarkPhase.CREATING_VCN);
        launch(vcn, () -> vcn.manageNew(location, CreateVcnDetails.builder().displayName("Benchmark network").cidrBlock(NETWORK)));
        progress.update(BenchmarkPhase.SETTING_UP_NETWORK);

        launch(nat, () -> nat.manageNew(location, CreateNatGatewayDetails.builder().displayName("NAT Gateway")));
        if (internet != null) {
            launch(internet, () -> internet.manageNew(location, CreateInternetGatewayDetails.builder().displayName("Internet Gateway").isEnabled(true)));
        }
        launch(privateRouteTable, () -> privateRouteTable.manageNew(location, () -> CreateRouteTableDetails.builder().displayName("Private Route Table")
                .routeRules(List.of(RouteRule.builder()
                        .destinationType(RouteRule.DestinationType.CidrBlock)
                        .destination("0.0.0.0/0")
                        .networkEntityId(nat.ocid())
                        .routeType(RouteRule.RouteType.Static)
                        .build()))));
        if (publicRouteTable != null) {
            assert internet != null;
            launch(publicRouteTable, () -> publicRouteTable.manageNew(location, () -> CreateRouteTableDetails.builder().displayName("Public Route Table")
                    .routeRules(List.of(RouteRule.builder()
                            .destinationType(RouteRule.DestinationType.CidrBlock)
                            .destination("0.0.0.0/0")
                            .networkEntityId(internet.ocid())
                            .routeType(RouteRule.RouteType.Static)
                            .build()))));
        }
        launch(privateSubnet, () -> privateSubnet.manageNew(location, CreateSubnetDetails.builder()
                .displayName("Private subnet")
                .prohibitPublicIpOnVnic(RELAY_MODE != SshRelayMode.PUBLIC_IP)
                .prohibitInternetIngress(RELAY_MODE != SshRelayMode.PUBLIC_IP)
                .cidrBlock(PRIVATE_SUBNET)));

        if (RELAY_MODE == SshRelayMode.RELAY_SERVER) {
            relayServerBuilder.launchAsResource();
        }

        if (publicSubnet != null) {
            launch(publicSubnet, () -> publicSubnet.manageNew(location, CreateSubnetDetails.builder()
                    .displayName("Relay subnet")
                    .cidrBlock(RELAY_SUBNET)));
        }

        SecurityList securityList = retry(() -> context.clients.vcn().forRegion(location).getSecurityList(GetSecurityListRequest.builder()
                .securityListId(vcn.getDefaultSecurityListId())
                .build()).getSecurityList());
        List<IngressSecurityRule> ingressRules = new ArrayList<>(securityList.getIngressSecurityRules());
        // allow all internal traffic
        ingressRules.add(IngressSecurityRule.builder()
                .source("10.0.0.0/8")
                .sourceType(IngressSecurityRule.SourceType.CidrBlock)
                .protocol("all")
                .isStateless(true)
                .build());
        if (RELAY_MODE == SshRelayMode.TCP_AGENT) {
            ingressRules.add(IngressSecurityRule.builder()
                    .source("0.0.0.0/0")
                    .sourceType(IngressSecurityRule.SourceType.CidrBlock)
                    .protocol("6")
                    .tcpOptions(TcpOptions.builder()
                            .destinationPortRange(PortRange.builder()
                                    .min(TcpAgentRelay.PORT)
                                    .max(TcpAgentRelay.PORT)
                                    .build())
                            .build())
                    .isStateless(true)
                    .build());
        }
        retry(() -> {
            Throttle.VCN.take();
            context.clients.vcn().forRegion(location).updateSecurityList(UpdateSecurityListRequest.builder()
                    .securityListId(vcn.getDefaultSecurityListId())
                    .updateSecurityListDetails(UpdateSecurityListDetails.builder()
                            .ingressSecurityRules(ingressRules)
                            .build())
                    .build());
            return null;
        });

        if (RELAY_MODE == SshRelayMode.TCP_AGENT) {
            launch(tcpRelayResource, tcpRelayResource::manage);
        }

        if (bastion != null) {
            launch(bastion, () -> bastion.manageNew(location, CreateBastionDetails.builder()
                    .name("ssh-gateway-bastion" + ThreadLocalRandom.current().nextLong())
                    .bastionType("standard")
                    .maxSessionTtlInSeconds((int) Duration.ofHours(3).toSeconds())
                    .clientCidrBlockAllowList(List.of("0.0.0.0/0"))));
        }

        progress.update(BenchmarkPhase.SETTING_UP_INSTANCES);
    }

    public final SubnetResource getPrivateSubnet() {
        return privateSubnet;
    }

    public final Compute.Launch computeBuilder(String instanceType) {
        Compute.Launch builder = compute.builder(instanceType, location, privateSubnet);
        return builder.access(switch (RELAY_MODE) {
            case BASTION -> {
                BastionSessionResource bastionSession = new BastionSessionResource(context).bastion(bastion);
                bastionSession.dependOn(builder.computeResource.require());
                yield new Compute.BastionAccess(bastionSession);
            }
            case PUBLIC_IP -> new Compute.PublicIpAccess();
            case RELAY_SERVER -> new Compute.SshRelayAccess(relayServerBuilder.resource());
            case TCP_AGENT -> new Compute.HttpRelayAccess(tcpRelayResource);
        });
    }

    public void addLifecycleDependency(List<PhasedResource.PhaseLock> locks) {
        lifecycleLocks.addAll(locks);
    }

    protected final void terminateRelayAsync() throws Exception {
    }

    @Override
    public void close() throws Exception {
        LOG.info("Terminating network resources");
        for (PhasedResource.PhaseLock lifecycleLock : lifecycleLocks) {
            lifecycleLock.close();
        }
    }

    public static <T, E extends Exception> T retry(ThrowingSupplier<T, E> callable) throws E {
        return retry(callable, () -> {});
    }

    public static <T, E extends Exception> T retry(ThrowingSupplier<T, E> callable, Runnable onFailure) throws E {
        return retry(callable, onFailure, 3);
    }

    static <T, E extends Exception> T retry(ThrowingSupplier<T, E> callable, Runnable onFailure, int n) throws E {
        E err = null;
        for (int i = 0; i < n; i++) {
            try {
                return callable.get();
            } catch (Exception e) {
                if (e instanceof InvalidatesBenchmarkException) {
                    throw e;
                }

                if (err == null) {
                    err = (E) e;
                } else {
                    err.addSuppressed(e);
                }
            }
            onFailure.run();
            try {
                TimeUnit.SECONDS.sleep(10);
            } catch (InterruptedException e) {
                PlatformDependent.throwException(e);
            }
        }
        throw err;
    }

    private enum SshRelayMode {
        BASTION,
        RELAY_SERVER,
        PUBLIC_IP,
        TCP_AGENT,
    }

    @Singleton
    public record Factory(
            ResourceContext resourceContext,
            Compute compute,
            TcpAgentRelay.Factory agentRelayFactory,
            SshFactory sshFactory
    ) {
    }
}
