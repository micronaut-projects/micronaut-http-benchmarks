package io.micronaut.benchmark.loadgen.oci.exec;

import java.io.IOException;

public interface CommandResult {
    default void check() throws IOException {
        checkStatus(0);
    }

    void checkStatus(int... expectedStatus) throws IOException;
}
