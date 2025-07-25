package io.micronaut.benchmark.loadgen.oci.exec;

import io.micronaut.benchmark.loadgen.oci.OutputListener;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public interface ProcessBuilder extends Closeable {
    void setOut(OutputStream stream);

    void setErr(OutputStream stream);

    default ProcessBuilder forwardOutput(OutputListener... listeners) {
        OutputListener.Stream stream = new OutputListener.Stream(List.of(listeners));
        setOut(stream);
        setErr(stream);
        return this;
    }

    ProcessHandle start() throws IOException;
}
