package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.core.model.CreateNatGatewayDetails;
import com.oracle.bmc.core.model.NatGateway;
import com.oracle.bmc.core.requests.CreateNatGatewayRequest;
import com.oracle.bmc.core.requests.DeleteNatGatewayRequest;
import com.oracle.bmc.core.requests.GetNatGatewayRequest;
import com.oracle.bmc.core.requests.ListNatGatewaysRequest;
import com.oracle.bmc.core.responses.ListNatGatewaysResponse;
import io.micronaut.benchmark.loadgen.oci.CompartmentCleaner;
import io.micronaut.benchmark.loadgen.oci.OciLocation;

import java.util.List;

public final class NatGatewayResource extends AbstractSimpleResource<NatGateway.LifecycleState> {
    private VcnResource vcn;

    public NatGatewayResource(ResourceContext context) {
        super(
                NatGateway.LifecycleState.Provisioning,
                NatGateway.LifecycleState.Available,
                NatGateway.LifecycleState.Terminating,
                NatGateway.LifecycleState.Terminated,
                context);
    }

    public static List<NatGateway> list(ResourceContext context, OciLocation location) {
        return CompartmentCleaner.list(
                context.clients.vcn().forRegion(location)::listNatGateways,
                ListNatGatewaysRequest.builder()
                        .compartmentId(location.compartmentId()),
                ListNatGatewaysRequest.Builder::page,
                ListNatGatewaysResponse::getOpcNextPage,
                ListNatGatewaysResponse::getItems
        );
    }

    public NatGatewayResource vcn(VcnResource vcn) {
        this.vcn = vcn;
        dependOn(vcn.require());
        return this;
    }

    public void manageNew(OciLocation location, CreateNatGatewayDetails.Builder details) throws Exception {
        manageNew(location, () -> {
            details.compartmentId(location.compartmentId());
            details.vcnId(vcn.ocid());
            NatGateway gw = context.clients.vcn().forRegion(location).createNatGateway(CreateNatGatewayRequest.builder().createNatGatewayDetails(details.build()).build()).getNatGateway();
            setPhase(gw.getLifecycleState());
            return gw.getId();
        });
    }

    @Override
    protected void delete(OciLocation location, String ocid) {
        context.clients.vcn().forRegion(location).deleteNatGateway(DeleteNatGatewayRequest.builder().natGatewayId(ocid).build());
    }

    @Override
    protected PhasePoller<String, NatGateway.LifecycleState> getPoller(OciLocation location) {
        return context.getPoller(location, NatGatewayResource.class, () -> PhasePoller.create(
                k -> context.clients.vcn().forRegion(location).getNatGateway(GetNatGatewayRequest.builder().natGatewayId(k).build()).getNatGateway().getLifecycleState(),
                () -> list(context, location),
                NatGateway::getId, NatGateway::getLifecycleState,
                NatGateway.LifecycleState.Terminated
        ));
    }
}
