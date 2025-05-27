package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.bastion.model.CreateSessionDetails;
import com.oracle.bmc.bastion.model.Session;
import com.oracle.bmc.bastion.model.SessionLifecycleState;
import com.oracle.bmc.bastion.model.SessionSummary;
import com.oracle.bmc.bastion.requests.CreateSessionRequest;
import com.oracle.bmc.bastion.requests.DeleteSessionRequest;
import com.oracle.bmc.bastion.requests.GetSessionRequest;
import com.oracle.bmc.bastion.requests.ListSessionsRequest;
import com.oracle.bmc.bastion.responses.ListSessionsResponse;
import io.micronaut.benchmark.loadgen.oci.CompartmentCleaner;
import io.micronaut.benchmark.loadgen.oci.OciLocation;

import java.util.List;

public final class BastionSessionResource extends AbstractSimpleResource<SessionLifecycleState> {
    private BastionResource bastion;
    private Session session;

    public BastionSessionResource(ResourceContext context) {
        super(SessionLifecycleState.Creating,
                SessionLifecycleState.Active,
                SessionLifecycleState.Deleting,
                SessionLifecycleState.Deleted,
                context);
    }

    public static List<SessionSummary> list(ResourceContext context, OciLocation location, BastionResource bastion) {
        return CompartmentCleaner.list(
                context.clients.bastion().forRegion(location)::listSessions,
                ListSessionsRequest.builder()
                        .bastionId(bastion.ocid()),
                ListSessionsRequest.Builder::page,
                ListSessionsResponse::getOpcNextPage,
                ListSessionsResponse::getItems
        );
    }

    public BastionSessionResource bastion(BastionResource bastion) {
        this.bastion = bastion;
        dependOn(bastion.require());
        return this;
    }

    public void manageNew(OciLocation location, CreateSessionDetails.Builder details) throws Exception {
        awaitLocks();

        details.bastionId(bastion.ocid());
        session = context.clients.bastion().forRegion(location).createSession(CreateSessionRequest.builder().createSessionDetails(details.build()).build()).getSession();
        manageExisting(location, session.getId());
    }

    @Override
    protected void delete(OciLocation location, String ocid) {
        context.clients.bastion().forRegion(location).deleteSession(DeleteSessionRequest.builder().sessionId(ocid).build());
    }

    @Override
    protected PhasePoller<String, SessionLifecycleState> getPoller(OciLocation location) {
        return context.getPoller(new PollerKey(location, bastion.ocid()), () -> PhasePoller.create(
                k -> context.clients.bastion().forRegion(location).getSession(GetSessionRequest.builder().sessionId(k).build()).getSession().getLifecycleState(),
                () -> list(context, location, bastion),
                SessionSummary::getId, SessionSummary::getLifecycleState
        ));
    }

    public String getBastionUserName() {
        return session.getBastionUserName();
    }

    private record PollerKey(OciLocation location, String bastionOcid) {
    }
}
