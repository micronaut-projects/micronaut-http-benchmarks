package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.auth.AbstractAuthenticationDetailsProvider;
import com.oracle.bmc.bastion.BastionClient;
import com.oracle.bmc.common.RegionalClientBuilder;
import com.oracle.bmc.computeinstanceagent.PluginClient;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.psql.PostgresqlClient;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.util.HashMap;
import java.util.Map;

/**
 * OCI clients that are region-specific (compute, VCN, identity).
 *
 * @param <C> The OCI client class
 */
public interface RegionalClient<C> {
    C forRegion(OciLocation location);

    @Factory
    @Singleton
    final class RegionalFactory {
        private final AbstractAuthenticationDetailsProvider authenticationDetailsProvider;

        RegionalFactory(AbstractAuthenticationDetailsProvider authenticationDetailsProvider) {
            this.authenticationDetailsProvider = authenticationDetailsProvider;
        }

        private <C> RegionalClient<C> clientFor(RegionalClientBuilder<? extends RegionalClientBuilder<?, C>, C> builder) {
            return new RegionalClient<>() {
                private final Map<String, C> cache = new HashMap<>();

                @Override
                public synchronized C forRegion(OciLocation location) {
                    return cache.computeIfAbsent(location.region(), reg -> builder.region(reg).build(authenticationDetailsProvider));
                }
            };
        }

        @Singleton
        RegionalClient<ComputeClient> compute(ComputeClient.Builder builder) {
            return clientFor(builder);
        }

        @Singleton
        RegionalClient<PluginClient> plugin(PluginClient.Builder builder) {
            return clientFor(builder);
        }

        @Singleton
        RegionalClient<VirtualNetworkClient> network(VirtualNetworkClient.Builder builder) {
            return clientFor(builder);
        }

        @Singleton
        RegionalClient<IdentityClient> identity(IdentityClient.Builder builder) {
            return clientFor(builder);
        }

        @Singleton
        RegionalClient<BastionClient> bastion(BastionClient.Builder builder) {
            return clientFor(builder);
        }

        @Singleton
        RegionalClient<PostgresqlClient> postgres(PostgresqlClient.Builder builder) {
            return clientFor(builder);
        }
    }
}
