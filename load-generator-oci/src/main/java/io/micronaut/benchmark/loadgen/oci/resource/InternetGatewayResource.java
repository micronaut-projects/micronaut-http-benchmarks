package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.core.model.CreateInternetGatewayDetails;
import com.oracle.bmc.core.model.InternetGateway;
import com.oracle.bmc.core.requests.CreateInternetGatewayRequest;
import com.oracle.bmc.core.requests.DeleteInternetGatewayRequest;
import com.oracle.bmc.core.requests.GetInternetGatewayRequest;
import com.oracle.bmc.core.requests.ListInternetGatewaysRequest;
import com.oracle.bmc.core.responses.ListInternetGatewaysResponse;
import io.micronaut.benchmark.loadgen.oci.CompartmentCleaner;
import io.micronaut.benchmark.loadgen.oci.OciLocation;

import java.util.List;

public final class InternetGatewayResource extends AbstractSimpleResource<InternetGateway.LifecycleState> {

    private VcnResource vcn;

    public InternetGatewayResource(ResourceContext context) {
        super(
                InternetGateway.LifecycleState.Provisioning,
                InternetGateway.LifecycleState.Available,
                InternetGateway.LifecycleState.Terminating,
                InternetGateway.LifecycleState.Terminated,
                context);
    }

    public static List<InternetGateway> list(ResourceContext context, OciLocation location) {
        return CompartmentCleaner.list(
                context.clients.vcn().forRegion(location)::listInternetGateways,
                ListInternetGatewaysRequest.builder()
                        .compartmentId(location.compartmentId()),
                ListInternetGatewaysRequest.Builder::page,
                ListInternetGatewaysResponse::getOpcNextPage,
                ListInternetGatewaysResponse::getItems
        );
    }

    public InternetGatewayResource vcn(VcnResource vcn) {
        this.vcn = vcn;
        dependOn(vcn.require());
        return this;
    }

    public void manageNew(OciLocation location, CreateInternetGatewayDetails.Builder details) throws Exception {
        manageNew(location, () -> {
            details.compartmentId(location.compartmentId());
            details.vcnId(vcn.ocid());
            InternetGateway gw = context.clients.vcn().forRegion(location).createInternetGateway(CreateInternetGatewayRequest.builder().createInternetGatewayDetails(details.build()).build()).getInternetGateway();
            setPhase(gw.getLifecycleState());
            return gw.getId();
        });
    }

    @Override
    protected void delete(OciLocation location, String ocid) {
        context.clients.vcn().forRegion(location).deleteInternetGateway(DeleteInternetGatewayRequest.builder().igId(ocid).build());
    }

    @Override
    protected PhasePoller<String, InternetGateway.LifecycleState> getPoller(OciLocation location) {
        return context.getPoller(location, InternetGatewayResource.class, () -> PhasePoller.create(
                k -> context.clients.vcn().forRegion(location).getInternetGateway(GetInternetGatewayRequest.builder().igId(k).build()).getInternetGateway().getLifecycleState(),
                () -> list(context, location),
                InternetGateway::getId, InternetGateway::getLifecycleState,
                InternetGateway.LifecycleState.Terminated
        ));
    }
}
