package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.EachProperty;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Locale;

/**
 * Computes different {@link LoadVariant}s based on configuration, e.g. different HTTP protocols to test and different
 * requests to make.
 */
@Singleton
public final class LoadManager {
    private final LoadConfiguration loadConfiguration;

    LoadManager(LoadConfiguration loadConfiguration) {
        this.loadConfiguration = loadConfiguration;
    }

    public List<LoadVariant> getLoadVariants() {
        return loadConfiguration.documents.stream()
                .flatMap(doc -> loadConfiguration.protocols.stream()
                        .filter(ProtocolSettings::isEnabled)
                        .map(prot -> new LoadVariant(loadName(prot.protocol(), doc), prot, doc)))
                .toList();
    }

    private static String loadName(Protocol protocol, LoadConfiguration.DocumentConfiguration doc) {
        return protocol.name().toLowerCase(Locale.ROOT) + "-" + doc.getName();
    }

    @ConfigurationProperties("load")
    record LoadConfiguration(List<ProtocolSettings> protocols, List<DocumentConfiguration> documents) {
        @EachProperty(value = "documents", list = true)
        interface DocumentConfiguration extends RequestDefinition.SampleRequestDefinition {
        }
    }
}
