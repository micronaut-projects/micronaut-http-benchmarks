package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.bastion.model.Bastion;
import com.oracle.bmc.bastion.model.BastionLifecycleState;
import com.oracle.bmc.bastion.model.BastionSummary;
import com.oracle.bmc.bastion.model.CreateBastionDetails;
import com.oracle.bmc.bastion.requests.CreateBastionRequest;
import com.oracle.bmc.bastion.requests.DeleteBastionRequest;
import com.oracle.bmc.bastion.requests.GetBastionRequest;
import com.oracle.bmc.bastion.requests.ListBastionsRequest;
import com.oracle.bmc.bastion.responses.ListBastionsResponse;
import io.micronaut.benchmark.loadgen.oci.CompartmentCleaner;
import io.micronaut.benchmark.loadgen.oci.OciLocation;

import java.util.List;

public final class BastionResource extends AbstractSimpleResource<BastionLifecycleState> {
    private SubnetResource subnet;

    public BastionResource(ResourceContext context) {
        super(
                BastionLifecycleState.Creating,
                BastionLifecycleState.Active,
                BastionLifecycleState.Deleting,
                BastionLifecycleState.Deleted,
                context);
    }

    public static List<BastionSummary> list(ResourceContext context, OciLocation location) {
        return CompartmentCleaner.list(
                context.clients.bastion().forRegion(location)::listBastions,
                ListBastionsRequest.builder()
                        .compartmentId(location.compartmentId()),
                ListBastionsRequest.Builder::page,
                ListBastionsResponse::getOpcNextPage,
                ListBastionsResponse::getItems
        );
    }

    public BastionResource subnet(SubnetResource resource) {
        this.subnet = resource;
        dependOn(resource.require());
        return this;
    }

    public void manageNew(OciLocation location, CreateBastionDetails.Builder details) throws Exception {
        awaitLocks();

        details.compartmentId(location.compartmentId());
        details.targetSubnetId(subnet.ocid());
        Bastion gw = context.clients.bastion().forRegion(location).createBastion(CreateBastionRequest.builder().createBastionDetails(details.build()).build()).getBastion();
        setPhase(gw.getLifecycleState());

        manageExisting(location, gw.getId());
    }

    @Override
    protected void delete(OciLocation location, String ocid) {
        context.clients.bastion().forRegion(location).deleteBastion(DeleteBastionRequest.builder().bastionId(ocid).build());
    }

    @Override
    protected PhasePoller<String, BastionLifecycleState> getPoller(OciLocation location) {
        return context.getPoller(location, BastionResource.class, () -> PhasePoller.create(
                k -> context.clients.bastion().forRegion(location).getBastion(GetBastionRequest.builder().bastionId(k).build()).getBastion().getLifecycleState(),
                () -> list(context, location),
                BastionSummary::getId, BastionSummary::getLifecycleState
        ));
    }
}
