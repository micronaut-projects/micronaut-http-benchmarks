package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.bastion.BastionClient;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.identity.IdentityClient;
import io.micronaut.benchmark.loadgen.oci.OciLocation;
import io.micronaut.benchmark.loadgen.oci.RegionalClient;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@Singleton
public final class ResourceContext {
    private static final Logger LOG = LoggerFactory.getLogger(ResourceContext.class);
    public final Clients clients;

    private final ConcurrentMap<Object, Poller> phasePollers = new ConcurrentHashMap<>();

    public ResourceContext(Clients clients) {
        this.clients = clients;
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

    private record PollerKey(OciLocation location, Class<?> discriminator) {
    }

    @Singleton
    public record Clients(
            RegionalClient<IdentityClient> identity,
            RegionalClient<ComputeClient> compute,
            RegionalClient<VirtualNetworkClient> vcn,
            RegionalClient<BastionClient> bastion
    ) {
    }

    public interface Poller {
        int size();

        void poll();
    }
}
