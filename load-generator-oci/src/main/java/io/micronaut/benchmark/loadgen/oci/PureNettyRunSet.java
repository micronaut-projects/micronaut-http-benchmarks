package io.micronaut.benchmark.loadgen.oci;

import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.List;

/**
 * Runs of the {@code test-case-pure-netty} submodule.
 */
@Singleton
public final class PureNettyRunSet implements FrameworkRunSet {
    private final JavaRunFactory javaRunFactory;

    public PureNettyRunSet(JavaRunFactory javaRunFactory) {
        this.javaRunFactory = javaRunFactory;
    }

    @Override
    public List<? extends FrameworkRun> getRuns() {
        return javaRunFactory.createJavaRuns("pure-netty")
                .shadowJar(Path.of("test-case-pure-netty/build/libs/test-case-pure-netty-all.jar"))
                .boundOn("Bound to https://0.0.0.0:8443")
                .build().toList();
    }
}
