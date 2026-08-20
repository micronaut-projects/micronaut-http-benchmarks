package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.core.annotation.Nullable;

import java.net.URI;

public record NixCacheAccess(String installable, @Nullable String defaultOutput, URI readUri, URI writeUri) {
    public String requireDefaultOutput() {
        if (defaultOutput == null) {
            throw new IllegalStateException("No default output for " + installable);
        }
        return defaultOutput;
    }
}
