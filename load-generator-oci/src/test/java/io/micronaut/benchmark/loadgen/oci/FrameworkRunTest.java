package io.micronaut.benchmark.loadgen.oci;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameworkRunTest {
    @Test
    void rejectsIncompleteSupplementalArtifactPairs() {
        assertThrows(IllegalArgumentException.class,
                () -> new FrameworkRun.Profiling("perf", "profile.data", "profile.jit.data", null));
        assertThrows(IllegalArgumentException.class,
                () -> new FrameworkRun.Profiling("perf", "profile.data", null, "profile-symbols"));
    }

    @Test
    void rejectsUnsafeComponentsInEveryProfilingArtifactSlot() {
        List<String> unsafeComponents = List.of(
                "",
                " ",
                ".",
                "..",
                "/tmp/profile.data",
                "nested/profile.data",
                "nested\\profile.data",
                "profile;data",
                "profile.data/"
        );

        for (String unsafeComponent : unsafeComponents) {
            assertThrows(IllegalArgumentException.class,
                    () -> new FrameworkRun.Profiling("perf", unsafeComponent, null, null));
            assertThrows(IllegalArgumentException.class,
                    () -> new FrameworkRun.Profiling("perf", "profile.data", unsafeComponent, null));
            assertThrows(IllegalArgumentException.class,
                    () -> new FrameworkRun.Profiling("perf", "profile.data", null, unsafeComponent));
        }
    }
}
