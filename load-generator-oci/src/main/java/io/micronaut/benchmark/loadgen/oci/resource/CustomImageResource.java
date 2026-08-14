package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.core.model.CreateImageDetails;
import com.oracle.bmc.core.model.Image;
import com.oracle.bmc.core.requests.CreateImageRequest;
import com.oracle.bmc.core.requests.GetImageRequest;
import com.oracle.bmc.core.requests.ListImagesRequest;
import com.oracle.bmc.core.responses.ListImagesResponse;
import io.micronaut.benchmark.loadgen.oci.CompartmentCleaner;
import io.micronaut.benchmark.loadgen.oci.OciLocation;

import java.util.List;

public final class CustomImageResource extends AbstractSimpleResource<Image.LifecycleState> {
    public CustomImageResource(ResourceContext context) {
        super(Image.LifecycleState.Importing, Image.LifecycleState.Available, null, null, context);
    }

    @Override
    protected List<Image.LifecycleState> phases() {
        return List.of(Image.LifecycleState.Importing, Image.LifecycleState.Available);
    }

    @Override
    protected void delete(OciLocation location, String ocid) {
        throw new UnsupportedOperationException();
    }

    public void manageNew(OciLocation location, CreateImageDetails.Builder details) throws Exception {
        manageNew(location, () -> {
            details.compartmentId(location.compartmentId());
            Image image = context.clients.compute().forRegion(location).createImage(CreateImageRequest.builder().createImageDetails(details.build()).build()).getImage();
            return new CreationResult<>(image.getId(), image.getLifecycleState());
        });
    }

    public static List<Image> list(ResourceContext context, OciLocation location) {
        return CompartmentCleaner.list(
                context.clients.compute().forRegion(location)::listImages,
                ListImagesRequest.builder()
                        .compartmentId(location.compartmentId()),
                ListImagesRequest.Builder::page,
                ListImagesResponse::getOpcNextPage,
                ListImagesResponse::getItems
        );
    }

    @Override
    protected PhasePoller<String, Image.LifecycleState> getPoller(OciLocation location) {
        return context.getPoller(location, CustomImageResource.class, () -> PhasePoller.create(
                k -> context.clients.compute().forRegion(location).getImage(GetImageRequest.builder().imageId(k).build()).getImage().getLifecycleState(),
                () -> list(context, location),
                Image::getId, Image::getLifecycleState
        ));
    }
}
