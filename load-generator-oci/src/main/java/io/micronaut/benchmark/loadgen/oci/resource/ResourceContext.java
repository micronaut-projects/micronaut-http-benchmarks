package io.micronaut.benchmark.loadgen.oci.resource;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.oracle.bmc.bastion.BastionClient;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.psql.PostgresqlClient;
import io.micronaut.benchmark.loadgen.oci.OciLocation;
import io.micronaut.benchmark.loadgen.oci.RegionalClient;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@Singleton
@Requires(notEnv = "test")
public final class ResourceContext {
    private static final Logger LOG = LoggerFactory.getLogger(ResourceContext.class);
    public final Clients clients;

    private final ConcurrentMap<Object, Poller> phasePollers = new ConcurrentHashMap<>();

    private final OutputStream eventLog;
    private final JsonMapper eventLogMapper;

    ResourceContext(Clients clients) throws IOException {
        this.clients = clients;

        eventLog = Files.newOutputStream(Path.of("output/events.log"));
        eventLogMapper = JsonMapper.builder()
                .registerSubtypes(LogEvent.class.getPermittedSubclasses())
                .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
                .findAndAddModules()
                .build();
    }

    @Scheduled(fixedDelay = "5s")
    void poll() {
        phasePollers.forEach((k, poller) -> {
            int n = poller.size();
            if (n > 0) {
                LOG.info("Polling {} items for {}", n, k);
            }
            poller.poll();
        });
    }

    @SuppressWarnings("unchecked")
    public <P extends Poller> P getPoller(Object key, Supplier<P> pollerSupplier) {
        return (P) phasePollers.computeIfAbsent(key, _ -> pollerSupplier.get());
    }

    public <P extends Poller> P getPoller(OciLocation location, Class<?> discriminator, Supplier<P> pollerSupplier) {
        return getPoller(new PollerKey(location, discriminator), pollerSupplier);
    }

    public synchronized void log(LogEvent event) {
        try {
            eventLogMapper.writeValue(eventLog, new LogEntry(Instant.now(), event));
            eventLog.write('\n');
            eventLog.flush();
        } catch (IOException e) {
            LOG.error("Error logging event", e);
        }
    }

    private record PollerKey(OciLocation location, Class<?> discriminator) {
        @Override
        public String toString() {
            return location.availabilityDomain() + "/" + discriminator.getSimpleName();
        }
    }

    @Singleton
    public record Clients(
            RegionalClient<IdentityClient> identity,
            RegionalClient<ComputeClient> compute,
            RegionalClient<VirtualNetworkClient> vcn,
            RegionalClient<BastionClient> bastion,
            RegionalClient<PostgresqlClient> postgres
    ) {
    }

    public interface Poller {
        int size();

        void poll();
    }

    public record LogEntry(
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Instant time,
            LogEvent event
    ) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY)
    public sealed interface LogEvent permits AbstractSimpleResource.DependencyEvent, PhasedResource.CloseLockEvent, PhasedResource.CreateLockEvent, PhasedResource.CreateResourceEvent, PhasedResource.NameEvent, PhasedResource.PhaseEvent {
    }
}
