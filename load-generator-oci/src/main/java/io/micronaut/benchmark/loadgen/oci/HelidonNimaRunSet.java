package io.micronaut.benchmark.loadgen.oci;

import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.List;

/**
 * Runs of the {@code test-case-helidon-nima} submodule.
 */
@Singleton
public final class HelidonNimaRunSet implements FrameworkRunSet {
    private final JavaRunFactory javaRunFactory;

    public HelidonNimaRunSet(JavaRunFactory javaRunFactory) {
        this.javaRunFactory = javaRunFactory;
    }

    @Override
    public List<? extends FrameworkRun> getRuns() {
        return javaRunFactory.createJavaRuns("helidon-nima")
                .shadowJar(Path.of("test-case-helidon-nima/build/libs/test-case-helidon-nima-all.jar"))
                .boundOn("Helidon bound")
                .build().toList();
    }
}
