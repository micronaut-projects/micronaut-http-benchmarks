package io.micronaut.benchmark.loadgen.oci;

import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.List;

/**
 * Runs of the {@code test-case-spring-boot} submodule.
 */
@Singleton
public final class SpringBootRunSet implements FrameworkRunSet {
    private final JavaRunFactory javaRunFactory;

    public SpringBootRunSet(JavaRunFactory javaRunFactory) {
        this.javaRunFactory = javaRunFactory;
    }

    @Override
    public List<? extends FrameworkRun> getRuns() {
        return javaRunFactory.createJavaRuns("spring-boot")
                .shadowJar(Path.of("test-case-spring-boot/build/libs/test-case-spring-boot.jar"))
                .boundOn("Started Main in")
                .build().toList();
    }
}
