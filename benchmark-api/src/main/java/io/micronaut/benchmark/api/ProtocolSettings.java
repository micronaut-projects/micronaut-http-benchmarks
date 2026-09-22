package io.micronaut.benchmark.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Different HTTP settings to test
 *
 * @param protocol          The HTTP protocol (TLS, version)
 * @param sharedConnections Number of shared connections
 * @param pipeliningLimit   Pipelining limit. Only {@code 1} is realistic, but a higher value can be used to stress the
 *                          HTTP parsing stack. HTTP/1.1 only
 * @param maxHttp2Streams   Maximum number of concurrent streams. HTTP/2 only
 * @param compileOps        Request rate used to size benchmark warmup
 * @param ops               Ops/s ramp for main benchmarking runs
 */
public record ProtocolSettings(
        Protocol protocol,
        int sharedConnections,
        int pipeliningLimit,
        int maxHttp2Streams,
        int compileOps,
        List<Integer> ops,
        Map<Double, String> sla
) {
    public ProtocolSettings {
        protocol = Objects.requireNonNull(protocol);
        ops = List.copyOf(Objects.requireNonNull(ops));
        sla = Map.copyOf(Objects.requireNonNull(sla));
    }
}
