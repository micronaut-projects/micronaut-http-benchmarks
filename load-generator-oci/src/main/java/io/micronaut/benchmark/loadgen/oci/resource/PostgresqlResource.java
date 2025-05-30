package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.psql.model.CreateDbSystemDetails;
import com.oracle.bmc.psql.model.DbSystem;
import com.oracle.bmc.psql.model.DbSystemSummary;
import com.oracle.bmc.psql.model.NetworkDetails;
import com.oracle.bmc.psql.model.OciOptimizedStorageDetails;
import com.oracle.bmc.psql.requests.CreateDbSystemRequest;
import com.oracle.bmc.psql.requests.DeleteDbSystemRequest;
import com.oracle.bmc.psql.requests.GetDbSystemRequest;
import com.oracle.bmc.psql.requests.ListDbSystemsRequest;
import com.oracle.bmc.psql.responses.ListDbSystemsResponse;
import io.micronaut.benchmark.loadgen.oci.CompartmentCleaner;
import io.micronaut.benchmark.loadgen.oci.OciLocation;

import java.util.List;

public final class PostgresqlResource extends AbstractSimpleResource<DbSystem.LifecycleState> {
    private SubnetResource subnet;
    private String privateIp;

    public PostgresqlResource(ResourceContext context) {
        super(DbSystem.LifecycleState.Creating,
                DbSystem.LifecycleState.Active,
                DbSystem.LifecycleState.Deleting,
                DbSystem.LifecycleState.Deleted,
                context);
    }

    public static List<DbSystemSummary> list(ResourceContext context, OciLocation location) {
        return CompartmentCleaner.list(
                context.clients.postgres().forRegion(location)::listDbSystems,
                ListDbSystemsRequest.builder()
                        .compartmentId(location.compartmentId()),
                ListDbSystemsRequest.Builder::page,
                ListDbSystemsResponse::getOpcNextPage,
                r -> r.getDbSystemCollection().getItems()
        );
    }

    public PostgresqlResource networkDetails(SubnetResource subnet, String privateIp) {
        this.subnet = subnet;
        this.privateIp = privateIp;
        dependOn(subnet.require());
        return this;
    }

    public void manageNew(OciLocation location, CreateDbSystemDetails.Builder details) throws Exception {
        manageNew(location, () -> {
            details.compartmentId(location.compartmentId());
            details.networkDetails(NetworkDetails.builder()
                    .subnetId(subnet.ocid())
                    .primaryDbEndpointPrivateIp(privateIp)
                    .build());
            details.systemType(DbSystem.SystemType.OciOptimizedStorage);
            details.storageDetails(OciOptimizedStorageDetails.builder()
                    .availabilityDomain(location.availabilityDomain())
                    .isRegionallyDurable(false)
                    .build());

            DbSystem dbSystem = context.clients.postgres().forRegion(location).createDbSystem(CreateDbSystemRequest.builder().createDbSystemDetails(details.build()).build()).getDbSystem();
            setPhase(dbSystem.getLifecycleState());
            return dbSystem.getId();
        });
    }

    @Override
    protected void delete(OciLocation location, String ocid) {
        context.clients.postgres().forRegion(location).deleteDbSystem(DeleteDbSystemRequest.builder().dbSystemId(ocid).build());
    }

    @Override
    protected PhasePoller<String, DbSystem.LifecycleState> getPoller(OciLocation location) {
        return context.getPoller(location, PostgresqlResource.class, () -> PhasePoller.create(
                k -> context.clients.postgres().forRegion(location).getDbSystem(GetDbSystemRequest.builder().dbSystemId(k).build()).getDbSystem().getLifecycleState(),
                () -> list(context, location),
                DbSystemSummary::getId, DbSystemSummary::getLifecycleState
        ));
    }
}
