package io.micronaut.benchmark.loadgen.oci.cmd;

import java.util.Map;

/**
 * A VanillaSsh host exposes SSH access to a remote via a standard TCP listener. This can be used to connect external
 * tools to this host.
 */
public interface VanillaSsh extends AutoCloseable {
    String host();

    default int port() {
        return 22;
    }

    default String username() {
        return "benchmark";
    }

    default Map<String, String> options() {
        return Map.of();
    }

    default void close() {
    }
}
