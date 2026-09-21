package io.micronaut.benchmark.loadgen.oci;

import java.net.URI;
import java.util.Objects;

public record NixCacheAccess(String installable, String defaultOutput, URI readUri, URI writeUri) {
    public NixCacheAccess {
        Objects.requireNonNull(defaultOutput, "defaultOutput");
    }
}
