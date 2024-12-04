package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.CreateInternetGatewayDetails;
import com.oracle.bmc.core.model.CreateNatGatewayDetails;
import com.oracle.bmc.core.model.CreateRouteTableDetails;
import com.oracle.bmc.core.model.CreateSubnetDetails;
import com.oracle.bmc.core.model.CreateVcnDetails;
import com.oracle.bmc.core.model.IngressSecurityRule;
import com.oracle.bmc.core.model.RouteRule;
import com.oracle.bmc.core.model.SecurityList;
import com.oracle.bmc.core.model.UpdateSecurityListDetails;
import com.oracle.bmc.core.model.Vcn;
import com.oracle.bmc.core.requests.CreateInternetGatewayRequest;
import com.oracle.bmc.core.requests.CreateNatGatewayRequest;
import com.oracle.bmc.core.requests.CreateRouteTableRequest;
import com.oracle.bmc.core.requests.CreateSubnetRequest;
import com.oracle.bmc.core.requests.CreateVcnRequest;
import com.oracle.bmc.core.requests.DeleteInternetGatewayRequest;
import com.oracle.bmc.core.requests.DeleteNatGatewayRequest;
import com.oracle.bmc.core.requests.DeleteRouteTableRequest;
import com.oracle.bmc.core.requests.DeleteSubnetRequest;
import com.oracle.bmc.core.requests.DeleteVcnRequest;
import com.oracle.bmc.core.requests.GetSecurityListRequest;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.core.requests.ListVnicAttachmentsRequest;
import com.oracle.bmc.core.requests.UpdateSecurityListRequest;
import com.oracle.bmc.model.BmcException;
import io.netty.channel.ConnectTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
     * @see #privateSubnetId
     */
    private static final String PRIVATE_SUBNET = "10.0.0.0/18";
    /**
     * Public subnet CIDR, only the SSH relay lives here.
     *
     * @see #publicSubnetId
     */
    private static final String RELAY_SUBNET = "10.0.254.0/24";

    /**
     * Location (compartment, region, AD) of this infrastructure.
     */
    protected final OciLocation location;
    /**
     * Infrastructure log directory.
     */
    protected final Path logDirectory;
    private final RegionalClient<VirtualNetworkClient> vcnClient;
    private final RegionalClient<ComputeClient> computeClient;
    private final Compute compute;

    private String publicSubnetId;
    /**
     * Subnet for all benchmark-related servers. Not internet-accessible, all transfers must happen through the SSH
     * relay.
     */
    protected String privateSubnetId;
    private String privateRouteTable;
    private String publicRouteTable;
    private String vcnId;
    private String natId;
    private String internetId;

    private RelayServer relayServer;
    private SshFactory.Relay relay;
    private Compute.Instance relayServerInstance;

    protected AbstractInfrastructure(OciLocation location, Path logDirectory, RegionalClient<VirtualNetworkClient> vcnClient, RegionalClient<ComputeClient> computeClient, Compute compute) {
        this.location = location;
        this.logDirectory = logDirectory;
        this.vcnClient = vcnClient;
        this.computeClient = computeClient;
        this.compute = compute;
    }

    protected final void setupBase(PhaseTracker.PhaseUpdater progress) throws Exception {
        try {
            Files.createDirectories(logDirectory);
        } catch (FileAlreadyExistsException ignored) {
        }

        progress.update(BenchmarkPhase.CREATING_VCN);
        Vcn vcn = createVcn(location);
        progress.update(BenchmarkPhase.SETTING_UP_NETWORK);
        vcnId = vcn.getId();
        Throttle.VCN.take();
        natId = vcnClient.forRegion(location).createNatGateway(CreateNatGatewayRequest.builder()
                .createNatGatewayDetails(CreateNatGatewayDetails.builder()
                        .compartmentId(location.compartmentId())
                        .vcnId(vcnId)
                        .displayName("NAT Gateway")
                        .build())
                .build()).getNatGateway().getId();
        Throttle.VCN.take();
        internetId = vcnClient.forRegion(location).createInternetGateway(CreateInternetGatewayRequest.builder()
                .createInternetGatewayDetails(CreateInternetGatewayDetails.builder()
                        .compartmentId(location.compartmentId())
                        .vcnId(vcnId)
                        .displayName("Internet Gateway")
                        .isEnabled(true)
                        .build())
                .build()).getInternetGateway().getId();
        Throttle.VCN.take();
        privateRouteTable = vcnClient.forRegion(location).createRouteTable(CreateRouteTableRequest.builder()
                .createRouteTableDetails(CreateRouteTableDetails.builder()
                        .compartmentId(location.compartmentId())
                        .vcnId(vcnId)
                        .routeRules(List.of(RouteRule.builder()
                                .destinationType(RouteRule.DestinationType.CidrBlock)
                                .destination("0.0.0.0/0")
                                .networkEntityId(natId)
                                .routeType(RouteRule.RouteType.Static)
                                .build()))
                        .build())
                .build()).getRouteTable().getId();
        Throttle.VCN.take();
        publicRouteTable = vcnClient.forRegion(location).createRouteTable(CreateRouteTableRequest.builder()
                .createRouteTableDetails(CreateRouteTableDetails.builder()
                        .compartmentId(location.compartmentId())
                        .vcnId(vcnId)
                        .routeRules(List.of(RouteRule.builder()
                                .destinationType(RouteRule.DestinationType.CidrBlock)
                                .destination("0.0.0.0/0")
                                .networkEntityId(internetId)
                                .routeType(RouteRule.RouteType.Static)
                                .build()))
                        .build())
                .build()).getRouteTable().getId();
        Throttle.VCN.take();
        privateSubnetId = vcnClient.forRegion(location).createSubnet(CreateSubnetRequest.builder()
                .createSubnetDetails(CreateSubnetDetails.builder()
                        .compartmentId(location.compartmentId())
                        .vcnId(vcnId)
                        .displayName("Private subnet")
                        .cidrBlock(PRIVATE_SUBNET)
                        .routeTableId(privateRouteTable)
                        .availabilityDomain(location.availabilityDomain())
                        .build())
                .build()).getSubnet().getId();
        Throttle.VCN.take();
        publicSubnetId = vcnClient.forRegion(location).createSubnet(CreateSubnetRequest.builder()
                .createSubnetDetails(CreateSubnetDetails.builder()
                        .compartmentId(location.compartmentId())
                        .vcnId(vcnId)
                        .displayName("Relay subnet")
                        .cidrBlock(RELAY_SUBNET)
                        .routeTableId(publicRouteTable)
                        .availabilityDomain(location.availabilityDomain())
                        .build())
                .build()).getSubnet().getId();

        Throttle.VCN.take();
        SecurityList securityList = retry(() -> vcnClient.forRegion(location).getSecurityList(GetSecurityListRequest.builder()
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
        retry(() -> {
            Throttle.VCN.take();
            vcnClient.forRegion(location).updateSecurityList(UpdateSecurityListRequest.builder()
                    .securityListId(vcn.getDefaultSecurityListId())
                    .updateSecurityListDetails(UpdateSecurityListDetails.builder()
                            .ingressSecurityRules(ingressRules)
                            .build())
                    .build());
            return null;
        });

        progress.update(BenchmarkPhase.SETTING_UP_INSTANCES);

        LOG.info("Creating relay server");
        relayServerInstance = compute.builder("relay-server", location, publicSubnetId)
                .publicIp(true)
                .launch();
    }

    /**
     * Lazily create the SSH relay. This method will block until the relay is available. Not thread-safe.
     *
     * @return The SSH relay
     */
    protected final SshFactory.Relay relay() throws Exception {
        if (relay == null) {
            relayServerInstance.awaitStartup();

            Throttle.COMPUTE.take();
            String vnic = computeClient.forRegion(location).listVnicAttachments(ListVnicAttachmentsRequest.builder()
                    .compartmentId(location.compartmentId())
                    .availabilityDomain(location.availabilityDomain())
                    .instanceId(relayServerInstance.id)
                    .build()).getItems().get(0).getVnicId();
            String publicIp = vcnClient.forRegion(location).getVnic(GetVnicRequest.builder()
                    .vnicId(vnic)
                    .build()).getVnic().getPublicIp();
            relayServer = new RelayServer(relayServerInstance, publicIp);
            relay = new SshFactory.Relay("opc", relayServer.publicIp);
        }
        return relay;
    }

    private Vcn createVcn(OciLocation location) throws InterruptedException {
        Vcn vcn;
        while (true) {
            try {
                Throttle.VCN.take();
                vcn = vcnClient.forRegion(location).createVcn(CreateVcnRequest.builder()
                        .createVcnDetails(CreateVcnDetails.builder()
                                .compartmentId(location.compartmentId())
                                .displayName("Benchmark network")
                                .cidrBlock(NETWORK)
                                .build())
                        .build()).getVcn();
                break;
            } catch (BmcException be) {
                if (be.getCause() instanceof ConnectTimeoutException) {
                    TimeUnit.SECONDS.sleep(10);
                    continue;
                }
                if (be.getStatusCode() == 400 && "LimitExceeded".equals(be.getServiceCode())) {
                    LOG.warn("Hit limit in CreateVcn operation. Likely you need to up your vcn-count limit. Waiting for 2m.");
                    TimeUnit.MINUTES.sleep(2);
                    continue;
                }
                if (be.getStatusCode() == 429 && "TooManyRequests".equals(be.getServiceCode())) {
                    TimeUnit.MINUTES.sleep(1);
                    continue;
                }
                throw be;
            }
        }
        return vcn;
    }

    protected final void terminateRelayAsync() {
        if (relayServer != null) {
            relayServer.instance.terminateAsync();
        }
    }

    @Override
    public void close() throws Exception {
        if (relayServer != null) {
            relayServer.close();
        }

        LOG.info("Terminating network resources");
        try {
            for (String subnet : new String[]{privateSubnetId, publicSubnetId}) {
                if (subnet != null) {
                    retry(() -> {
                        Throttle.VCN.takeUninterruptibly();
                        return vcnClient.forRegion(location).deleteSubnet(DeleteSubnetRequest.builder().subnetId(subnet).build());
                    });
                }
            }
            for (String routeTable : new String[]{privateRouteTable, publicRouteTable}) {
                if (routeTable != null) {
                    retry(() -> {
                        Throttle.VCN.takeUninterruptibly();
                        return vcnClient.forRegion(location).deleteRouteTable(DeleteRouteTableRequest.builder().rtId(routeTable).build());
                    });
                }
            }
            if (internetId != null) {
                retry(() -> {
                    Throttle.VCN.takeUninterruptibly();
                    return vcnClient.forRegion(location).deleteInternetGateway(DeleteInternetGatewayRequest.builder().igId(internetId).build());
                });
            }
            if (natId != null) {
                retry(() -> {
                    Throttle.VCN.takeUninterruptibly();
                    return vcnClient.forRegion(location).deleteNatGateway(DeleteNatGatewayRequest.builder().natGatewayId(natId).build());
                });
            }
            if (vcnId != null) {
                retry(() -> {
                    Throttle.VCN.takeUninterruptibly();
                    return vcnClient.forRegion(location).deleteVcn(DeleteVcnRequest.builder().vcnId(vcnId).build());
                });
            }
        } catch (BmcException e) {
            LOG.warn("Failed to terminate benchmark network. Cleanup will happen after all benchmarks complete.", e);
        }
    }

    static <T> T retry(Callable<T> callable) throws Exception {
        return retry(callable, () -> {});
    }

    static <T> T retry(Callable<T> callable, Runnable onFailure) throws Exception {
        Exception err = null;
        for (int i = 0; i < 3; i++) {
            try {
                return callable.call();
            } catch (InvalidatesBenchmarkException ibe) {
                throw ibe;
            } catch (Exception e) {
                if (err == null) {
                    err = e;
                } else {
                    err.addSuppressed(e);
                }
            }
            onFailure.run();
            TimeUnit.SECONDS.sleep(10);
        }
        throw err;
    }

    private record RelayServer(
            Compute.Instance instance,
            String publicIp
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            instance.close();
        }
    }
}
