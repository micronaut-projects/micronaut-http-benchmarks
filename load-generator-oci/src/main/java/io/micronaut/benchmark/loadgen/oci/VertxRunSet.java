package io.micronaut.benchmark.loadgen.oci;

import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.List;

/**
 * Runs of the {@code test-case-vertx} submodule.
 */
@Singleton
public final class VertxRunSet implements FrameworkRunSet {
    private final JavaRunFactory javaRunFactory;

    public VertxRunSet(JavaRunFactory javaRunFactory) {
        this.javaRunFactory = javaRunFactory;
    }

    @Override
    public List<? extends FrameworkRun> getRuns() {
        return javaRunFactory.createJavaRuns("vertx")
                .shadowJar(Path.of("test-case-vertx/build/libs/test-case-vertx-all.jar"))
                .boundOn("Vertx bound")
                .build().toList();
    }
}
