package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.core.model.CreateVcnDetails;
import com.oracle.bmc.core.model.Vcn;
import com.oracle.bmc.core.requests.CreateVcnRequest;
import com.oracle.bmc.core.requests.DeleteVcnRequest;
import com.oracle.bmc.core.requests.GetVcnRequest;
import com.oracle.bmc.core.requests.ListVcnsRequest;
import com.oracle.bmc.core.responses.ListVcnsResponse;
import io.micronaut.benchmark.loadgen.oci.CompartmentCleaner;
import io.micronaut.benchmark.loadgen.oci.OciLocation;

import java.util.ArrayList;
import java.util.List;

public final class VcnResource extends AbstractSimpleResource<Vcn.LifecycleState> {
    private RouteTableResource defaultRouteTable;
    private String defaultSecurityListId;

    public VcnResource(ResourceContext context) {
        super(Vcn.LifecycleState.Provisioning,
                Vcn.LifecycleState.Available,
                Vcn.LifecycleState.Terminating,
                Vcn.LifecycleState.Terminated,
                context);
    }

    public static List<Vcn> list(ResourceContext context, OciLocation location) {
        return CompartmentCleaner.list(
                context.clients.vcn().forRegion(location)::listVcns,
                ListVcnsRequest.builder().compartmentId(location.compartmentId()),
                ListVcnsRequest.Builder::page,
                ListVcnsResponse::getOpcNextPage,
                ListVcnsResponse::getItems
        );
    }

    public void setDefaultRouteTable(RouteTableResource defaultRouteTable) {
        this.defaultRouteTable = defaultRouteTable;
    }

    public String getDefaultSecurityListId() {
        return defaultSecurityListId;
    }

    public void manageNew(OciLocation location, CreateVcnDetails.Builder details) throws Exception {
        manageNew(location, () -> {
            details.compartmentId(location.compartmentId());
            Vcn vcn = context.clients.vcn().forRegion(location).createVcn(CreateVcnRequest.builder().createVcnDetails(details.build()).build()).getVcn();
            this.defaultSecurityListId = vcn.getDefaultSecurityListId();
            setPhase(vcn.getLifecycleState());
            return vcn.getId();
        });
    }

    @Override
    public List<PhaseLock> require() {
        List<PhaseLock> phaseLocks = new ArrayList<>(requireVcnOnly());
        if (defaultRouteTable != null) {
            phaseLocks.addAll(defaultRouteTable.require());
        }
        return phaseLocks;
    }

    public List<PhaseLock> requireVcnOnly() {
        return super.require();
    }

    @Override
    protected void delete(OciLocation location, String ocid) {
        context.clients.vcn().forRegion(location).deleteVcn(DeleteVcnRequest.builder().vcnId(ocid).build());
    }

    @Override
    protected PhasePoller<String, Vcn.LifecycleState> getPoller(OciLocation location) {
        return context.getPoller(location, VcnResource.class, () -> PhasePoller.create(
                k -> context.clients.vcn().forRegion(location).getVcn(GetVcnRequest.builder().vcnId(k).build()).getVcn().getLifecycleState(),
                () -> list(context, location),
                Vcn::getId, Vcn::getLifecycleState,
                Vcn.LifecycleState.Terminated
        ));
    }
}
