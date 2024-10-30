package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record LoadVariant(
        @JsonIgnore
        String name,
        ProtocolSettings protocol,
        @JsonIgnore
        RequestDefinition.SampleRequestDefinition definition
) {
    public String getLoadName() {
        return definition.getName();
    }
}
