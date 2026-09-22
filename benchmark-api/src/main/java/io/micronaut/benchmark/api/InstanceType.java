package io.micronaut.benchmark.api;

public record InstanceType(String shape, float ocpus, float memoryInGb, String platform, Integer diskPerformanceUnits) {
}
