package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.core.model.CreateRouteTableDetails;
import com.oracle.bmc.core.model.RouteTable;
import com.oracle.bmc.core.requests.CreateRouteTableRequest;
import com.oracle.bmc.core.requests.DeleteRouteTableRequest;
import com.oracle.bmc.core.requests.GetRouteTableRequest;
import com.oracle.bmc.core.requests.ListRouteTablesRequest;
import com.oracle.bmc.core.responses.ListRouteTablesResponse;
import io.micronaut.benchmark.loadgen.oci.CompartmentCleaner;
import io.micronaut.benchmark.loadgen.oci.OciLocation;

import java.util.List;
import java.util.function.Supplier;

public final class RouteTableResource extends AbstractSimpleResource<RouteTable.LifecycleState> {
    private VcnResource vcn;
    private boolean defaultForVcn;

    public RouteTableResource(ResourceContext context) {
        super(
                RouteTable.LifecycleState.Provisioning,
                RouteTable.LifecycleState.Available,
                RouteTable.LifecycleState.Terminating,
                RouteTable.LifecycleState.Terminated,
                context);
    }

    public void setDefaultForVcn(boolean defaultForVcn) {
        this.defaultForVcn = defaultForVcn;
    }

    public static List<RouteTable> list(ResourceContext context, OciLocation location) {
        return CompartmentCleaner.list(
                context.clients.vcn().forRegion(location)::listRouteTables,
                ListRouteTablesRequest.builder()
                        .compartmentId(location.compartmentId()),
                ListRouteTablesRequest.Builder::page,
                ListRouteTablesResponse::getOpcNextPage,
                ListRouteTablesResponse::getItems
        );
    }

    public RouteTableResource vcn(VcnResource vcn) {
        this.vcn = vcn;
        if (!defaultForVcn) {
            dependOn(vcn.require());
        }
        return this;
    }

    public void manageNew(OciLocation location, Supplier<CreateRouteTableDetails.Builder> details) throws Exception {
        manageNew(location, () -> {
            CreateRouteTableDetails.Builder d = details.get();
            d.compartmentId(location.compartmentId());
            d.vcnId(vcn.ocid());
            RouteTable rt = context.clients.vcn().forRegion(location).createRouteTable(CreateRouteTableRequest.builder().createRouteTableDetails(d.build()).build()).getRouteTable();
            setPhase(rt.getLifecycleState());
            return rt.getId();
        });
    }

    @Override
    protected void delete(OciLocation location, String ocid) {
        if (defaultForVcn) {
            // implicitly deleted with VCN
            setPhase(RouteTable.LifecycleState.Terminated);
        } else {
            context.clients.vcn().forRegion(location).deleteRouteTable(DeleteRouteTableRequest.builder().rtId(ocid).build());
        }
    }

    @Override
    protected PhasePoller<String, RouteTable.LifecycleState> getPoller(OciLocation location) {
        return context.getPoller(location, RouteTableResource.class, () -> PhasePoller.create(
                k -> context.clients.vcn().forRegion(location).getRouteTable(GetRouteTableRequest.builder().rtId(k).build()).getRouteTable().getLifecycleState(),
                () -> list(context, location),
                RouteTable::getId, RouteTable::getLifecycleState,
                RouteTable.LifecycleState.Terminated
        ));
    }
}
