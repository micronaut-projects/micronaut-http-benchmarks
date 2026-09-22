package io.micronaut.benchmark.api;

import java.util.List;

public record ExperimentRequirements(int version, InstanceType instanceType, String kernel, List<String> attachments) {
    public ExperimentRequirements {
        attachments = List.copyOf(attachments);
    }
}
