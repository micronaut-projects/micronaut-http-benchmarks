package io.micronaut.benchmark.loadgen.oci;

import java.net.URI;

public record NixCacheAccess(String installable, String defaultDerivation, URI readUri, URI writeUri) {
}
