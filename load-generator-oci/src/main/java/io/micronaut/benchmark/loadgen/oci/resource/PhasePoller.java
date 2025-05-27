package io.micronaut.benchmark.loadgen.oci.resource;

import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.requests.BmcRequest;
import io.micronaut.benchmark.loadgen.oci.AbstractInfrastructure;
import io.micronaut.benchmark.loadgen.oci.CompartmentCleaner;
import io.micronaut.core.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public abstract class PhasePoller<K, P> implements ResourceContext.Poller {
    private static final Logger LOG = LoggerFactory.getLogger(PhasePoller.class);
    private final Map<K, Subscription> subscriptions = new HashMap<>();
    private int listThreshold = 5;

    private PhasePoller() {
    }

    @Override
    public synchronized int size() {
        return subscriptions.size();
    }

    public final synchronized void subscribeUntil(K key, PhasedResource<P> resource, P until) {
        Subscription subscription = new Subscription(resource, until);
        if (!subscription.isComplete(resource.getCurrentPhase())) {
            subscriptions.put(key, subscription);
        }
    }

    @Override
    public final void poll() {
        Map<K, Subscription> copy;
        synchronized (this) {
            copy = new HashMap<>(subscriptions);
        }
        Set<K> done = new HashSet<>();
        if (copy.size() > listThreshold) {
            listStates(copy.keySet()).forEach((k, p) -> {
                Subscription subscription = copy.get(k);
                if (subscription != null) {
                    subscription.resource.setPhase(p);
                    if (subscription.isComplete(p)) {
                        done.add(k);
                    }
                }
            });
        } else {
            copy.forEach((k, s) -> {
                P p = getState(k);
                s.resource.setPhase(p);
                if (s.isComplete(p)) {
                    done.add(k);
                }
            });
        }
        if (!done.isEmpty()) {
            synchronized (this) {
                subscriptions.keySet().removeAll(done);
            }
        }
    }

    public static <K> Builder<K, ?, ?, ?, ?, ?> builder() {
        return new Builder<>();
    }

    public static <K, P, SUM> PhasePoller<K, P> create(Function<K, P> getOne, Supplier<List<SUM>> list, Function<SUM, K> getKey, Function<SUM, P> getPhase) {
        return create(getOne, list, getKey, getPhase, null);
    }

    public static <K, P, SUM> PhasePoller<K, P> create(Function<K, P> getOne, Supplier<List<SUM>> list, Function<SUM, K> getKey, Function<SUM, P> getPhase, @Nullable P substitute404) {
        return new PhasePoller<>() {
            @Override
            protected Map<K, P> listStates(Collection<K> expected) {
                List<SUM> l = list.get();
                Map<K, P> map = new HashMap<>();
                for (SUM summary : l) {
                    map.put(getKey.apply(summary), getPhase.apply(summary));
                }
                for (K k : expected) {
                    if (substitute404 != null) {
                        map.putIfAbsent(k, substitute404);
                    } else if (!map.containsKey(k)) {
                        LOG.warn("Missing item {}", k);
                    }
                }
                return map;
            }

            @Override
            protected P getState(K key) {
                try {
                    return getOne.apply(key);
                } catch (BmcException be) {
                    if (substitute404 != null && be.getStatusCode() == 404) {
                        return substitute404;
                    }
                    throw be;
                }
            }
        };
    }

    protected abstract Map<K, P> listStates(Collection<K> expected);

    protected abstract P getState(K key);

    private class Subscription {
        final PhasedResource<P> resource;

        final P until;

        private Subscription(PhasedResource<P> resource, P until) {
            this.resource = resource;
            this.until = until;
        }
        boolean isComplete(P phase) {
            return resource.compare(phase, until) >= 0;
        }

    }

    public static final class Builder<K, P, SUM, BUILDER extends BmcRequest.Builder<REQ, ?>, REQ extends BmcRequest<?>, RESP> {
        private Function<K, P> fetchOne;
        private Function<REQ, RESP> call;
        private Supplier<BUILDER> builder;
        private BiConsumer<BUILDER, String> setPage;
        private Function<RESP, String> getNextPage;
        private Function<RESP, List<SUM>> getItems;
        private Function<SUM, K> getKey;
        private Function<SUM, P> getPhase;

        public <P> Builder<K, P, SUM, BUILDER, REQ, RESP> fetchOne(Function<K, P> fetchOne) {
            Builder<K, P, SUM, BUILDER, REQ, RESP> mapped = (Builder<K, P, SUM, BUILDER, REQ, RESP>) this;
            mapped.fetchOne = fetchOne;
            return mapped;
        }

        public <BUILDER extends BmcRequest.Builder<REQ, ?>, REQ extends BmcRequest<?>> Builder<K, P, SUM, BUILDER, REQ, RESP> listBuilder(Supplier<BUILDER> builder) {
            Builder<K, P, SUM, BUILDER, REQ, RESP> mapped = (Builder<K, P, SUM, BUILDER, REQ, RESP>) this;
            mapped.builder = builder;
            return mapped;
        }

        public <RESP> Builder<K, P, SUM, BUILDER, REQ, RESP> list(Function<REQ, RESP> call) {
            Builder<K, P, SUM, BUILDER, REQ, RESP> mapped = (Builder<K, P, SUM, BUILDER, REQ, RESP>) this;
            mapped.builder = builder;
            return mapped;
        }

        public <SUM> Builder<K, P, SUM, BUILDER, REQ, RESP> paginate(BiConsumer<BUILDER, String> setPage, Function<RESP, String> getNextPage, Function<RESP, List<SUM>> getItems) {
            Builder<K, P, SUM, BUILDER, REQ, RESP> mapped = (Builder<K, P, SUM, BUILDER, REQ, RESP>) this;
            mapped.setPage = setPage;
            mapped.getNextPage = getNextPage;
            mapped.getItems = getItems;
            return mapped;
        }

        public Builder<K, P, SUM, BUILDER, REQ, RESP> extractSummary(Function<SUM, K> getKey, Function<SUM, P> getPhase) {
            Builder<K, P, SUM, BUILDER, REQ, RESP> mapped = (Builder<K, P, SUM, BUILDER, REQ, RESP>) this;
            mapped.getKey = getKey;
            mapped.getPhase = getPhase;
            return mapped;
        }

        public PhasePoller<K, P> build() {
            return create(fetchOne, () -> CompartmentCleaner.list(r -> AbstractInfrastructure.retry(() -> call.apply(r)), builder.get(), setPage, getNextPage, getItems), getKey, getPhase);
        }
    }
}
