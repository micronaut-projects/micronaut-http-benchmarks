package io.micronaut.benchmark.loadgen.oci;

import java.net.URI;
import java.util.Objects;

public record NixCacheAccess(String defaultOutput, URI readUri) {
    public NixCacheAccess {
        Objects.requireNonNull(defaultOutput, "defaultOutput");
    }
}
