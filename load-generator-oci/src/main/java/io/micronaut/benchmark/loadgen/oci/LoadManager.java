package io.micronaut.benchmark.loadgen.oci;

import jakarta.inject.Singleton;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Computes different {@link LoadVariant}s based on configuration, e.g. different HTTP protocols to test and different
 * requests to make.
 */
@Singleton
public final class LoadManager {
    private final BenchmarkMetadata metadata;

    LoadManager(BenchmarkMetadata metadata) {
        this.metadata = metadata;
    }

    public List<LoadVariant> getLoadVariants() {
        BenchmarkMetadata.Suite suite = metadata.suite();
        List<ProtocolSettings> protocols = suite.protocols().values().stream()
                .sorted(Comparator.comparing(ProtocolSettings::protocol))
                .toList();
        return suite.documents().stream()
                .flatMap(doc -> protocols.stream()
                        .map(prot -> new LoadVariant(loadName(prot.protocol(), doc), prot, doc)))
                .toList();
    }

    private static String loadName(Protocol protocol, SuiteRequest doc) {
        return protocol.name().toLowerCase(Locale.ROOT) + "-" + doc.getName();
    }

}
