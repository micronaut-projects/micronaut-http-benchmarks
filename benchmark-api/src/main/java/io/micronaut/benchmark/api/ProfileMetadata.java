package io.micronaut.benchmark.api;

import io.micronaut.core.annotation.Nullable;

public record ProfileMetadata(String tool, String artifact, @Nullable String injectedArtifact,
                              @Nullable String symbolDirectory) {

    public ProfileMetadata {
        if ((injectedArtifact == null) != (symbolDirectory == null)) {
            throw new IllegalArgumentException("Profiling injectedArtifact and symbolDirectory must be declared together");
        }
    }

    public ProfileMetadata(String tool, String artifact) {
        this(tool, artifact, null, null);
    }

}
