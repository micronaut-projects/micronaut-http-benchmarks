package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.bastion.BastionClient;
import com.oracle.bmc.bastion.model.BastionSummary;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.InternetGateway;
import com.oracle.bmc.core.model.NatGateway;
import com.oracle.bmc.core.model.RouteRule;
import com.oracle.bmc.core.model.RouteTable;
import com.oracle.bmc.core.model.Subnet;
import com.oracle.bmc.core.model.Vcn;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.Compartment;
import com.oracle.bmc.psql.PostgresqlClient;
import com.oracle.bmc.requests.BmcRequest;
import io.micronaut.benchmark.loadgen.oci.resource.AbstractSimpleResource;
import io.micronaut.benchmark.loadgen.oci.resource.BastionResource;
import io.micronaut.benchmark.loadgen.oci.resource.CompartmentResource;
import io.micronaut.benchmark.loadgen.oci.resource.ComputeResource;
import io.micronaut.benchmark.loadgen.oci.resource.InternetGatewayResource;
import io.micronaut.benchmark.loadgen.oci.resource.NatGatewayResource;
import io.micronaut.benchmark.loadgen.oci.resource.ResourceContext;
import io.micronaut.benchmark.loadgen.oci.resource.RouteTableResource;
import io.micronaut.benchmark.loadgen.oci.resource.SubnetResource;
import io.micronaut.benchmark.loadgen.oci.resource.VcnResource;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * This class deletes all resources in a compartment, before and after a benchmark.
 */
@Singleton
public record CompartmentCleaner(
        ResourceContext context,
        RegionalClient<IdentityClient> identityClient,
        RegionalClient<ComputeClient> computeClient,
        RegionalClient<VirtualNetworkClient> vcnClient,
        RegionalClient<BastionClient> bastionClient,
        RegionalClient<PostgresqlClient> postgresqlClient,
        Compute compute
) {
    private static final Logger LOG = LoggerFactory.getLogger(CompartmentCleaner.class);

    /**
     * Clean a compartment in a given region.
     *
     * @param location The compartment to clean
     * @param delete If {@code true}, delete the compartment itself at the end of this method.
     */
    public void cleanCompartment(OciLocation location, boolean delete) throws Exception {
        String compartment = location.compartmentId();
        LOG.info("Cleaning compartment {} in region {}...", compartment, location.region());

        CompartmentResource r = adaptCompartment(location, delete);
        if (delete) {
            r.manageExisting(location, location.compartmentId());
        } else {
            ((NotDeletingCompartmentResource) r).clear();
        }
    }

    private CompartmentResource adaptCompartment(OciLocation location, boolean delete) {
        CompartmentResource compartmentResource = delete ? new CompartmentResource(context) : new NotDeletingCompartmentResource(context);

        Map<String, AbstractSimpleResource<?>> resources = new HashMap<>();
        boolean anyVcns = false;

        // scan the subnets because bastions and instances depend on them
        for (Vcn vcn : VcnResource.list(context, location)) {
            VcnResource resource = new VcnResource(context);
            resource.dependOn(compartmentResource.require());
            resource.setPhase(vcn.getLifecycleState());
            resources.put(vcn.getId(), resource);
            anyVcns = true;

            RouteTableResource rtr = new RouteTableResource(context);
            rtr.setDefaultForVcn(true);
            resources.put(vcn.getDefaultRouteTableId(), rtr);
            resource.setDefaultRouteTable(rtr);
        }
        List<SubnetResource> subnetResources = new ArrayList<>();
        List<Subnet> subnets = SubnetResource.list(context, location);
        if (anyVcns) {
            for (Subnet subnet : subnets) {
                SubnetResource resource = new SubnetResource(context);
                resource.setPhase(subnet.getLifecycleState());
                resource.dependOn(resources.get(subnet.getVcnId()).require());
                subnetResources.add(resource);
                resources.put(subnet.getId(), resource);
            }
        }
        // bastions and instances take the longest to delete, so delete them first
        for (BastionSummary bastion : BastionResource.list(context, location)) {
            BastionResource resource = new BastionResource(context);
            resource.setPhase(bastion.getLifecycleState());
            if (bastion.getTargetVcnId() != null) {
                resource.dependOn(resources.get(bastion.getTargetVcnId()).require());
            }
            if (bastion.getTargetSubnetId() != null) {
                resource.dependOn(resources.get(bastion.getTargetSubnetId()).require());
            }
            delete(location, bastion.getId(), resource);
        }
        for (Instance instance : ComputeResource.list(context, location)) {
            ComputeResource resource = new ComputeResource(context);
            resource.setPhase(instance.getLifecycleState());
            // don't know which subnets this instance uses, so just depend on all of them
            for (SubnetResource subnet : subnetResources) {
                resource.dependOn(subnet.require());
            }
            delete(location, instance.getId(), resource);
        }
        // scan sub-compartments next
        for (Compartment subCompartment : CompartmentResource.list(context, location)) {
            CompartmentResource resource = adaptCompartment(new OciLocation(subCompartment.getId(), location.region(), location.availabilityDomain()), true);
            resource.setPhase(subCompartment.getLifecycleState());
            compartmentResource.dependOn(resource.require());
            delete(location, subCompartment.getId(), resource);
        }
        // other network resources take little time to delete
        if (anyVcns) {
            for (NatGateway natGateway : NatGatewayResource.list(context, location)) {
                NatGatewayResource resource = new NatGatewayResource(context);
                resource.setPhase(natGateway.getLifecycleState());
                resource.dependOn(resources.get(natGateway.getVcnId()).require());
                resources.put(natGateway.getId(), resource);
            }
            for (InternetGateway internetGateway : InternetGatewayResource.list(context, location)) {
                InternetGatewayResource resource = new InternetGatewayResource(context);
                resource.setPhase(internetGateway.getLifecycleState());
                resource.dependOn(resources.get(internetGateway.getVcnId()).require());
                resources.put(internetGateway.getId(), resource);
            }
            for (RouteTable routeTable : RouteTableResource.list(context, location)) {
                RouteTableResource resource = (RouteTableResource) resources.get(routeTable.getId());
                if (resource == null) {
                    resource = new RouteTableResource(context);
                }
                resource.setPhase(routeTable.getLifecycleState());
                resource.dependOn(((VcnResource) resources.get(routeTable.getVcnId())).requireVcnOnly());
                for (RouteRule rule : routeTable.getRouteRules()) {
                    resource.dependOn(resources.get(rule.getNetworkEntityId()).require());
                }
                resources.put(routeTable.getId(), resource);
            }
            for (Subnet subnet : subnets) {
                resources.get(subnet.getId()).dependOn(resources.get(subnet.getRouteTableId()).require());
            }
        }
        resources.forEach((ocid, r) -> delete(location, ocid, r));

        return compartmentResource;
    }

    private void delete(OciLocation location, String ocid, AbstractSimpleResource<?> resource) {
        Thread.ofVirtual()
                .name("delete-" + ocid)
                .start(() -> {
                    try {
                        resource.manageExisting(location, ocid);
                    } catch (Exception e) {
                        LOG.error("Failed to delete resource {}", ocid, e);
                    }
                });
    }

    public static <T, BUILDER extends BmcRequest.Builder<REQ, ?>, REQ extends BmcRequest<?>, RESP> List<T> list(
            Function<REQ, RESP> call,
            BUILDER builder,
            BiConsumer<BUILDER, String> setPage,
            Function<RESP, String> getNextPage,
            Function<RESP, List<T>> getItems
    ) {
        List<T> result = new ArrayList<>();
        while (true) {
            REQ req = builder.build();
            RESP resp = Infrastructure.retry(() -> call.apply(req));
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

    private static class NotDeletingCompartmentResource extends CompartmentResource {
        public NotDeletingCompartmentResource(ResourceContext context) {
            super(context);
        }

        void clear() throws InterruptedException {
            awaitUnlocked(Compartment.LifecycleState.Active);
        }
    }
}
