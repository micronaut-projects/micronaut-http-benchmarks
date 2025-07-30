package io.micronaut.benchmark.loadgen.oci.cmd;

import java.io.IOException;
import java.util.stream.IntStream;

public interface CommandResult {
    int status() throws IOException;

    default void check() throws IOException {
        checkStatus(0);
    }

    default void checkStatus(int... expectedStatus) throws IOException {
        int exitStatus = status();
        if (IntStream.of(expectedStatus).noneMatch(i -> i == exitStatus)) {
            throw new IOException("Exit status: " + exitStatus);
        }
    }
}
