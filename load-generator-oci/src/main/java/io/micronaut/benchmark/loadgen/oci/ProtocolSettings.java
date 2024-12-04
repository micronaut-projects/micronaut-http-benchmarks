package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.core.bind.annotation.Bindable;

import java.util.List;

/**
 * Different HTTP settings to test
 *
 * @param protocol          The HTTP protocol (TLS, version)
 * @param sharedConnections Number of shared connections
 * @param pipeliningLimit   Pipelining limit. Only {@code 1} is realistic, but a higher value can be used to stress the
 *                          HTTP parsing stack. HTTP/1.1 only
 * @param maxHttp2Streams   Maximum number of concurrent streams. HTTP/2 only
 * @param compileOps        Ops/s to use during JVM warmup, and during PGO runs
 * @param ops               Ops/s ramp for main benchmarking runs
 */
@EachProperty(value = "load.protocols", list = true)
public record ProtocolSettings(
        Protocol protocol,
        int sharedConnections,
        @Bindable(defaultValue = "1")
        int pipeliningLimit,
        @Bindable(defaultValue = "1")
        int maxHttp2Streams,
        int compileOps,
        List<Integer> ops
) {
}
