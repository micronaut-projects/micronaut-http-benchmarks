package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.core.model.ComputeImageCapabilitySchema;
import com.oracle.bmc.core.model.ComputeImageCapabilitySchemaSummary;
import com.oracle.bmc.core.model.CreateComputeImageCapabilitySchemaDetails;
import com.oracle.bmc.core.requests.CreateComputeImageCapabilitySchemaRequest;
import com.oracle.bmc.core.requests.DeleteComputeImageCapabilitySchemaRequest;
import com.oracle.bmc.core.requests.GetComputeImageCapabilitySchemaRequest;
import com.oracle.bmc.core.requests.ListComputeImageCapabilitySchemasRequest;
import com.oracle.bmc.core.responses.ListComputeImageCapabilitySchemasResponse;
import com.oracle.bmc.model.BmcException;
import io.micronaut.benchmark.loadgen.oci.CompartmentCleaner;
import io.micronaut.benchmark.loadgen.oci.OciLocation;

import java.util.List;

public final class ComputeImageCapabilitySchemaResource extends AbstractSimpleResource<ComputeImageCapabilitySchema.LifecycleState> {
    public ComputeImageCapabilitySchemaResource(ResourceContext context) {
        super(
                ComputeImageCapabilitySchema.LifecycleState.Creating,
                ComputeImageCapabilitySchema.LifecycleState.Active,
                null,
                ComputeImageCapabilitySchema.LifecycleState.Deleted,
                context);
    }

    @Override
    protected List<ComputeImageCapabilitySchema.LifecycleState> phases() {
        return List.of(
                ComputeImageCapabilitySchema.LifecycleState.Creating,
                ComputeImageCapabilitySchema.LifecycleState.Active,
                ComputeImageCapabilitySchema.LifecycleState.Deleted
        );
    }

    public void manageNew(OciLocation location, CreateComputeImageCapabilitySchemaDetails.Builder details) throws Exception {
        manageNew(location, () -> {
            details.compartmentId(location.compartmentId());
            ComputeImageCapabilitySchema schema = context.clients.compute().forRegion(location)
                    .createComputeImageCapabilitySchema(CreateComputeImageCapabilitySchemaRequest.builder()
                            .createComputeImageCapabilitySchemaDetails(details.build())
                            .build())
                    .getComputeImageCapabilitySchema();
            setPhase(schema.getLifecycleState());
            return schema.getId();
        });
    }

    public static List<ComputeImageCapabilitySchema> list(ResourceContext context, OciLocation location, String imageId) {
        return CompartmentCleaner.list(
                        context.clients.compute().forRegion(location)::listComputeImageCapabilitySchemas,
                        ListComputeImageCapabilitySchemasRequest.builder()
                                .compartmentId(location.compartmentId())
                                .imageId(imageId),
                        ListComputeImageCapabilitySchemasRequest.Builder::page,
                        ListComputeImageCapabilitySchemasResponse::getOpcNextPage,
                        ListComputeImageCapabilitySchemasResponse::getItems)
                .stream()
                .map(summary -> get(context, location, summary))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    private static java.util.Optional<ComputeImageCapabilitySchema> get(
            ResourceContext context,
            OciLocation location,
            ComputeImageCapabilitySchemaSummary summary) {
        try {
            return java.util.Optional.of(context.clients.compute().forRegion(location)
                    .getComputeImageCapabilitySchema(GetComputeImageCapabilitySchemaRequest.builder()
                            .computeImageCapabilitySchemaId(summary.getId())
                            .build())
                    .getComputeImageCapabilitySchema());
        } catch (BmcException exception) {
            if (exception.getStatusCode() == 404) {
                return java.util.Optional.empty();
            }
            throw exception;
        }
    }

    @Override
    protected void delete(OciLocation location, String ocid) {
        context.clients.compute().forRegion(location).deleteComputeImageCapabilitySchema(
                DeleteComputeImageCapabilitySchemaRequest.builder()
                        .computeImageCapabilitySchemaId(ocid)
                        .build());
    }

    @Override
    protected PhasePoller<String, ComputeImageCapabilitySchema.LifecycleState> getPoller(OciLocation location) {
        return context.getPoller(location, ComputeImageCapabilitySchemaResource.class, () -> PhasePoller.create(
                key -> context.clients.compute().forRegion(location)
                        .getComputeImageCapabilitySchema(GetComputeImageCapabilitySchemaRequest.builder()
                                .computeImageCapabilitySchemaId(key)
                                .build())
                        .getComputeImageCapabilitySchema()
                        .getLifecycleState(),
                () -> list(context, location, null),
                ComputeImageCapabilitySchema::getId,
                ComputeImageCapabilitySchema::getLifecycleState,
                ComputeImageCapabilitySchema.LifecycleState.Deleted));
    }
}
