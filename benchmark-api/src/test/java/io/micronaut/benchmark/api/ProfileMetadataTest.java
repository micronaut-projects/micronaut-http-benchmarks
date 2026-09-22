package io.micronaut.benchmark.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfileMetadataTest {
    @Test
    void rejectsIncompleteSupplementalArtifactPairs() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProfileMetadata("perf", "profile.data", "profile.jit.data", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProfileMetadata("perf", "profile.data", null, "profile-symbols"));
    }

}
