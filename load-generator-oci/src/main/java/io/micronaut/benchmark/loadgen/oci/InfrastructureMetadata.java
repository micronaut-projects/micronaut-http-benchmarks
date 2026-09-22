package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.api.InstanceType;
import io.micronaut.benchmark.api.Nix;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import jakarta.inject.Singleton;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

@Singleton
public final class InfrastructureMetadata {
    private final Document document;

    public InfrastructureMetadata(Nix nix, JsonMapper mapper) throws Exception {
        try (var log = new OutputListener.Stream(List.of(new OutputListener.Log(LoggerFactory.getLogger(getClass()), Level.INFO)))) {
            String system = nix.capture(List.of("eval", "--impure", "--raw", "--expr", "builtins.currentSystem"), log);
            document = mapper.readValue(nix.build(log, "./nix#lib.infrastructure." + system + ".metadata").toFile(), Document.class);
        }
    }

    public InstanceType instanceType(String name) {
        return document.instanceTypes().get(name);
    }

    public String kernel() {
        return document.kernel();
    }

    public Document document() {
        return document;
    }

    public record Document(Map<String, InstanceType> instanceTypes, String kernel) {
    }
}
