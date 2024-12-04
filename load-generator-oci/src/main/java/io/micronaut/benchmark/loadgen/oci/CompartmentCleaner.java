package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.InternetGateway;
import com.oracle.bmc.core.model.NatGateway;
import com.oracle.bmc.core.model.RouteTable;
import com.oracle.bmc.core.model.Subnet;
import com.oracle.bmc.core.model.UpdateRouteTableDetails;
import com.oracle.bmc.core.model.Vcn;
import com.oracle.bmc.core.requests.DeleteInternetGatewayRequest;
import com.oracle.bmc.core.requests.DeleteNatGatewayRequest;
import com.oracle.bmc.core.requests.DeleteRouteTableRequest;
import com.oracle.bmc.core.requests.DeleteSubnetRequest;
import com.oracle.bmc.core.requests.DeleteVcnRequest;
import com.oracle.bmc.core.requests.ListInstancesRequest;
import com.oracle.bmc.core.requests.ListInternetGatewaysRequest;
import com.oracle.bmc.core.requests.ListNatGatewaysRequest;
import com.oracle.bmc.core.requests.ListRouteTablesRequest;
import com.oracle.bmc.core.requests.ListSubnetsRequest;
import com.oracle.bmc.core.requests.ListVcnsRequest;
import com.oracle.bmc.core.requests.UpdateRouteTableRequest;
import com.oracle.bmc.core.responses.ListInstancesResponse;
import com.oracle.bmc.core.responses.ListInternetGatewaysResponse;
import com.oracle.bmc.core.responses.ListNatGatewaysResponse;
import com.oracle.bmc.core.responses.ListRouteTablesResponse;
import com.oracle.bmc.core.responses.ListSubnetsResponse;
import com.oracle.bmc.core.responses.ListVcnsResponse;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.Compartment;
import com.oracle.bmc.identity.requests.DeleteCompartmentRequest;
import com.oracle.bmc.identity.requests.ListCompartmentsRequest;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.requests.BmcRequest;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * This class deletes all resources in a compartment, before and after a benchmark.
 */
@Singleton
public final class CompartmentCleaner {
    private static final Logger LOG = LoggerFactory.getLogger(CompartmentCleaner.class);

    private final RegionalClient<IdentityClient> identityClient;
    private final RegionalClient<ComputeClient> computeClient;
    private final RegionalClient<VirtualNetworkClient> vcnClient;
    private final Compute compute;

    public CompartmentCleaner(RegionalClient<IdentityClient> identityClient, RegionalClient<ComputeClient> computeClient, RegionalClient<VirtualNetworkClient> vcnClient, Compute compute) {
        this.identityClient = identityClient;
        this.computeClient = computeClient;
        this.vcnClient = vcnClient;
        this.compute = compute;
    }

    /**
     * Clean a compartment in a given region.
     *
     * @param location The compartment to clean
     * @param delete If {@code true}, delete the compartment itself at the end of this method.
     */
    public void cleanCompartment(OciLocation location, boolean delete) {
        String compartment = location.compartmentId();
        LOG.info("Cleaning compartment {} in region {}...", compartment, location.region());

        // delete sub-compartments recursively
        Throttle.IDENTITY.takeUninterruptibly();
        identityClient.forRegion(location).listCompartments(ListCompartmentsRequest.builder()
                        .compartmentId(compartment)
                        .build())
                .getItems()
                .parallelStream()
                .filter(c -> c.getLifecycleState() == Compartment.LifecycleState.Active)
                .forEach(child -> cleanCompartment(new OciLocation(child.getId(), location.region(), location.availabilityDomain()), true));

        // trigger deletion of compute instances
        Throttle.COMPUTE.takeUninterruptibly();
        for (Instance instance : list(
                computeClient.forRegion(location)::listInstances,
                ListInstancesRequest.builder()
                        .compartmentId(compartment),
                ListInstancesRequest.Builder::page,
                ListInstancesResponse::getOpcNextPage,
                ListInstancesResponse::getItems
        )) {
            Instance.LifecycleState lifecycleState = instance.getLifecycleState();
            if (lifecycleState != Instance.LifecycleState.Terminated && lifecycleState != Instance.LifecycleState.Terminating) {
                //noinspection resource
                compute.new Instance(location, instance.getId()).terminateAsync();
            }
        }

        // delete route tables
        Throttle.VCN.takeUninterruptibly();
        List<RouteTable> routeTables = list(
                vcnClient.forRegion(location)::listRouteTables,
                ListRouteTablesRequest.builder()
                        .compartmentId(compartment),
                ListRouteTablesRequest.Builder::page,
                ListRouteTablesResponse::getOpcNextPage,
                ListRouteTablesResponse::getItems
        );
        for (RouteTable routeTable : routeTables) {
            if (!routeTable.getRouteRules().isEmpty()) {
                LOG.info("Clearing route table {}", routeTable.getDisplayName());
                Throttle.VCN.takeUninterruptibly();
                vcnClient.forRegion(location).updateRouteTable(UpdateRouteTableRequest.builder()
                        .rtId(routeTable.getId())
                        .updateRouteTableDetails(UpdateRouteTableDetails.builder()
                                .routeRules(Collections.emptyList())
                                .build())
                        .build());
            }
        }

        // wait for any compute instances to terminate before we can delete the subnets
        while (true) {
            Throttle.COMPUTE.takeUninterruptibly();
            List<Instance> instances = list(
                    computeClient.forRegion(location)::listInstances,
                    ListInstancesRequest.builder()
                            .compartmentId(compartment),
                    ListInstancesRequest.Builder::page,
                    ListInstancesResponse::getOpcNextPage,
                    ListInstancesResponse::getItems
            );
            int terminating = 0;
            for (Instance instance : instances) {
                switch (instance.getLifecycleState()) {
                    case Terminating -> terminating++;
                    case Terminated -> {} // fine
                    default -> throw new IllegalStateException("Unexpected state for compute instance " + instance.getId() + " " + instance.getLifecycleState() + ". All instances should be terminating.");
                }
            }
            if (terminating == 0) {
                break;
            }
            LOG.info("Waiting for {} instances to terminate", terminating);
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        // delete subnets
        Throttle.VCN.takeUninterruptibly();
        for (Subnet subnet : list(
                vcnClient.forRegion(location)::listSubnets,
                ListSubnetsRequest.builder()
                        .compartmentId(compartment),
                ListSubnetsRequest.Builder::page,
                ListSubnetsResponse::getOpcNextPage,
                ListSubnetsResponse::getItems
        )) {
            LOG.info("Deleting subnet {}", subnet.getDisplayName());
            try {
                Throttle.VCN.takeUninterruptibly();
                vcnClient.forRegion(location).deleteSubnet(DeleteSubnetRequest.builder()
                        .subnetId(subnet.getId())
                        .build());
            } catch (BmcException e) {
                LOG.warn("Failed to delete subnet: {}", e.getMessage());
            }
        }

        Throttle.VCN.takeUninterruptibly();
        List<Vcn> vcns = list(
                vcnClient.forRegion(location)::listVcns,
                ListVcnsRequest.builder()
                        .compartmentId(compartment),
                ListVcnsRequest.Builder::page,
                ListVcnsResponse::getOpcNextPage,
                ListVcnsResponse::getItems
        );

        // delete route tables
        for (RouteTable routeTable : routeTables) {
            if (vcns.stream().noneMatch(vcn -> vcn.getDefaultRouteTableId().equals(routeTable.getId()))) {
                LOG.info("Deleting route table {}", routeTable.getDisplayName());
                try {
                    Throttle.VCN.takeUninterruptibly();
                    vcnClient.forRegion(location).deleteRouteTable(DeleteRouteTableRequest.builder()
                            .rtId(routeTable.getId())
                            .build());
                } catch (BmcException e) {
                    LOG.warn("Failed to delete route table: {}", e.getMessage());
                }
            }
        }

        // delete internet gateways
        Throttle.VCN.takeUninterruptibly();
        for (InternetGateway ig : list(
                vcnClient.forRegion(location)::listInternetGateways,
                ListInternetGatewaysRequest.builder()
                        .compartmentId(compartment),
                ListInternetGatewaysRequest.Builder::page,
                ListInternetGatewaysResponse::getOpcNextPage,
                ListInternetGatewaysResponse::getItems
        )) {
            LOG.info("Deleting internet gateway {}", ig.getDisplayName());
            Throttle.VCN.takeUninterruptibly();
            vcnClient.forRegion(location).deleteInternetGateway(DeleteInternetGatewayRequest.builder()
                    .igId(ig.getId())
                    .build());
        }

        // delete NAT gateways
        Throttle.VCN.takeUninterruptibly();
        for (NatGateway nat : list(
                vcnClient.forRegion(location)::listNatGateways,
                ListNatGatewaysRequest.builder()
                        .compartmentId(compartment),
                ListNatGatewaysRequest.Builder::page,
                ListNatGatewaysResponse::getOpcNextPage,
                ListNatGatewaysResponse::getItems
        )) {
            LOG.info("Deleting NAT gateway {}", nat.getDisplayName());
            Throttle.VCN.takeUninterruptibly();
            vcnClient.forRegion(location).deleteNatGateway(DeleteNatGatewayRequest.builder()
                    .natGatewayId(nat.getId())
                    .build());
        }

        // delete VCNs
        for (Vcn vcn : vcns) {
            LOG.info("Deleting VCN {}", vcn.getDisplayName());
            try {
                Throttle.VCN.takeUninterruptibly();
                vcnClient.forRegion(location).deleteVcn(DeleteVcnRequest.builder()
                        .vcnId(vcn.getId())
                        .build());
            } catch (BmcException e) {
                LOG.warn("Failed to delete VCN: {}", e.getMessage());
            }
        }

        if (delete) {
            // delete compartment if requested
            LOG.info("Deleting compartment {}", compartment);
            Throttle.IDENTITY.takeUninterruptibly();
            identityClient.forRegion(location).deleteCompartment(DeleteCompartmentRequest.builder()
                    .compartmentId(compartment)
                    .build());
        }
    }

    private <T, BUILDER extends BmcRequest.Builder<REQ, ?>, REQ extends BmcRequest<?>, RESP> List<T> list(
            Function<REQ, RESP> call,
            BUILDER builder,
            BiConsumer<BUILDER, String> setPage,
            Function<RESP, String> getNextPage,
            Function<RESP, List<T>> getItems
    ) {
        List<T> result = new ArrayList<>();
        while (true) {
            REQ req = builder.build();
            RESP resp = call.apply(req);
            result.addAll(getItems.apply(resp));
            String nextPage = getNextPage.apply(resp);
            if (nextPage == null) {
                break;
            } else {
                setPage.accept(builder, nextPage);
            }
        }
        return result;
    }
}
