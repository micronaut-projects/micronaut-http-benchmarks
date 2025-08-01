package io.micronaut.benchmark.loadgen.oci.cmd;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public interface ProcessHandle extends Closeable {
    void interrupt() throws IOException;

    void kill() throws IOException;

    CommandResult waitFor() throws InterruptedException;

    CommandResult waitFor(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException;

    boolean isOpen();
}
