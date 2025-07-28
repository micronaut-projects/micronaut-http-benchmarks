package io.micronaut.benchmark.relay;

import java.io.IOException;

public interface CommandResult {
    default void check() throws IOException {
        checkStatus(0);
    }

    void checkStatus(int... expectedStatus) throws IOException;
}
