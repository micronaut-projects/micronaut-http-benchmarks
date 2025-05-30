package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.LaunchInstanceDetails;
import com.oracle.bmc.core.requests.GetInstanceRequest;
import com.oracle.bmc.core.requests.LaunchInstanceRequest;
import com.oracle.bmc.core.requests.ListInstancesRequest;
import com.oracle.bmc.core.requests.TerminateInstanceRequest;
import com.oracle.bmc.core.responses.ListInstancesResponse;
import io.micronaut.benchmark.loadgen.oci.CompartmentCleaner;
import io.micronaut.benchmark.loadgen.oci.OciLocation;

import java.util.List;
import java.util.function.Supplier;

public final class ComputeResource extends AbstractSimpleResource<Instance.LifecycleState> {
    public ComputeResource(ResourceContext context) {
        super(
                Instance.LifecycleState.Provisioning,
                Instance.LifecycleState.Running,
                Instance.LifecycleState.Terminating,
                Instance.LifecycleState.Terminated,
                context);
    }

    @Override
    protected List<Instance.LifecycleState> phases() {
        return List.of(
                Instance.LifecycleState.Provisioning,
                Instance.LifecycleState.Starting,
                Instance.LifecycleState.Running,
                Instance.LifecycleState.Stopped,
                Instance.LifecycleState.Terminating,
                Instance.LifecycleState.Terminated
        );
    }

    @Override
    protected void delete(OciLocation location, String ocid) {
        context.clients.compute().forRegion(location).terminateInstance(TerminateInstanceRequest.builder().instanceId(ocid).build());
    }

    public void manageNew(OciLocation location, Supplier<LaunchInstanceDetails.Builder> details) throws Exception {
        manageNew(location, () -> {
            LaunchInstanceDetails.Builder d = details.get();
            d.compartmentId(location.compartmentId());
            d.availabilityDomain(location.availabilityDomain());
            Instance i = context.clients.compute().forRegion(location).launchInstance(LaunchInstanceRequest.builder().launchInstanceDetails(d.build()).build()).getInstance();
            setPhase(i.getLifecycleState());
            return i.getId();
        });
    }

    public static List<Instance> list(ResourceContext context, OciLocation location) {
        return CompartmentCleaner.list(
                context.clients.compute().forRegion(location)::listInstances,
                ListInstancesRequest.builder()
                        .compartmentId(location.compartmentId()),
                ListInstancesRequest.Builder::page,
                ListInstancesResponse::getOpcNextPage,
                ListInstancesResponse::getItems
        );
    }

    @Override
    protected PhasePoller<String, Instance.LifecycleState> getPoller(OciLocation location) {
        return context.getPoller(location, ComputeResource.class, () -> PhasePoller.create(
                k -> context.clients.compute().forRegion(location).getInstance(GetInstanceRequest.builder().instanceId(k).build()).getInstance().getLifecycleState(),
                () -> list(context, location),
                Instance::getId, Instance::getLifecycleState
        ));
    }
}
