package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.identity.model.Compartment;
import com.oracle.bmc.identity.requests.DeleteCompartmentRequest;
import com.oracle.bmc.identity.requests.GetCompartmentRequest;
import com.oracle.bmc.identity.requests.ListCompartmentsRequest;
import com.oracle.bmc.identity.responses.ListCompartmentsResponse;
import io.micronaut.benchmark.loadgen.oci.CompartmentCleaner;
import io.micronaut.benchmark.loadgen.oci.OciLocation;

import java.util.List;

public class CompartmentResource extends AbstractSimpleResource<Compartment.LifecycleState> {
    public CompartmentResource(ResourceContext context) {
        super(
                Compartment.LifecycleState.Creating,
                Compartment.LifecycleState.Active,
                Compartment.LifecycleState.Deleting,
                Compartment.LifecycleState.Deleted,
                context);
    }

    public static List<Compartment> list(ResourceContext context, OciLocation location) {
        return CompartmentCleaner.list(
                context.clients.identity().forRegion(location)::listCompartments,
                ListCompartmentsRequest.builder()
                        .compartmentId(location.compartmentId()),
                ListCompartmentsRequest.Builder::page,
                ListCompartmentsResponse::getOpcNextPage,
                ListCompartmentsResponse::getItems
        );
    }

    @Override
    protected void delete(OciLocation location, String ocid) {
        context.clients.identity().forRegion(location).deleteCompartment(DeleteCompartmentRequest.builder().compartmentId(ocid).build());
    }

    @Override
    protected PhasePoller<String, Compartment.LifecycleState> getPoller(OciLocation location) {
        return context.getPoller(location, CompartmentResource.class, () -> PhasePoller.create(
                k -> context.clients.identity().forRegion(location).getCompartment(GetCompartmentRequest.builder().compartmentId(k).build()).getCompartment().getLifecycleState(),
                () -> list(context, location),
                Compartment::getId, Compartment::getLifecycleState
        ));
    }
}
