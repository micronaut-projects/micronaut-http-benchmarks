package io.micronaut.benchmark.loadgen.oci.cmd;

import java.io.Closeable;
import java.net.InetSocketAddress;

public interface PortForwardHandle extends Closeable {
    InetSocketAddress localAddress();
}
