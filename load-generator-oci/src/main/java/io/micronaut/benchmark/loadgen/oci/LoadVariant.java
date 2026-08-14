package io.micronaut.benchmark.loadgen.oci;

public record LoadVariant(
        String name,
        ProtocolSettings protocol,
        SuiteRequest definition
) {
}
