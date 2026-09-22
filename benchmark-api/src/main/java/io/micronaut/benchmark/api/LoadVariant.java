package io.micronaut.benchmark.api;

public record LoadVariant(
        String name,
        ProtocolSettings protocol,
        SuiteRequest definition
) {
}
