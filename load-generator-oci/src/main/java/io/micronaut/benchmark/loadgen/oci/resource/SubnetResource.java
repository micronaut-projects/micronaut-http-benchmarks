package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.core.model.CreateSubnetDetails;
import com.oracle.bmc.core.model.Subnet;
import com.oracle.bmc.core.requests.CreateSubnetRequest;
import com.oracle.bmc.core.requests.DeleteSubnetRequest;
import com.oracle.bmc.core.requests.GetSubnetRequest;
import com.oracle.bmc.core.requests.ListSubnetsRequest;
import com.oracle.bmc.core.responses.ListSubnetsResponse;
import io.micronaut.benchmark.loadgen.oci.CompartmentCleaner;
import io.micronaut.benchmark.loadgen.oci.OciLocation;

import java.util.List;

public final class SubnetResource extends AbstractSimpleResource<Subnet.LifecycleState> {
    private VcnResource vcn;
    private RouteTableResource routeTable;

    public SubnetResource(ResourceContext context) {
        super(
                Subnet.LifecycleState.Provisioning,
                Subnet.LifecycleState.Available,
                Subnet.LifecycleState.Terminating,
                Subnet.LifecycleState.Terminated,
                context);
    }

    public static List<Subnet> list(ResourceContext context, OciLocation location) {
        return CompartmentCleaner.list(
                context.clients.vcn().forRegion(location)::listSubnets,
                ListSubnetsRequest.builder()
                        .compartmentId(location.compartmentId()),
                ListSubnetsRequest.Builder::page,
                ListSubnetsResponse::getOpcNextPage,
                ListSubnetsResponse::getItems
        );
    }

    public SubnetResource vcn(VcnResource vcn) {
        this.vcn = vcn;
        dependOn(vcn.require());
        return this;
    }

    public SubnetResource routeTable(RouteTableResource routeTable) {
        this.routeTable = routeTable;
        dependOn(routeTable.require());
        return this;
    }

    public void manageNew(OciLocation location, CreateSubnetDetails.Builder details) throws Exception {
        awaitLocks();

        details.compartmentId(location.compartmentId());
        details.availabilityDomain(location.availabilityDomain());
        details.vcnId(vcn.ocid());
        details.routeTableId(routeTable.ocid());
        Subnet sn = context.clients.vcn().forRegion(location).createSubnet(CreateSubnetRequest.builder().createSubnetDetails(details.build()).build()).getSubnet();
        setPhase(sn.getLifecycleState());

        manageExisting(location, sn.getId());
    }

    @Override
    protected void delete(OciLocation location, String ocid) {
        context.clients.vcn().forRegion(location).deleteSubnet(DeleteSubnetRequest.builder().subnetId(ocid).build());
    }

    @Override
    protected PhasePoller<String, Subnet.LifecycleState> getPoller(OciLocation location) {
        return context.getPoller(location, SubnetResource.class, () -> PhasePoller.create(
                k -> context.clients.vcn().forRegion(location).getSubnet(GetSubnetRequest.builder().subnetId(k).build()).getSubnet().getLifecycleState(),
                () -> list(context, location),
                Subnet::getId, Subnet::getLifecycleState,
                Subnet.LifecycleState.Terminated
        ));
    }
}
