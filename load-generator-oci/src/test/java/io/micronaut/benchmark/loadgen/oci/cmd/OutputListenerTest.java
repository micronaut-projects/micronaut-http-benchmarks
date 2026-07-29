package io.micronaut.benchmark.loadgen.oci.cmd;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputListenerTest {
    @Test
    public void waiterSimple() {
        OutputListener.Waiter waiter = new OutputListener.Waiter(ByteBuffer.wrap("foo".getBytes(StandardCharsets.UTF_8)));
        assertFalse(waiter.found());
        waiter.onData(ByteBuffer.wrap("foo".getBytes(StandardCharsets.UTF_8)));
        assertTrue(waiter.found());
    }

    @Test
    public void waiterIgnoresCsi() {
        OutputListener.Waiter waiter = new OutputListener.Waiter(ByteBuffer.wrap("foo".getBytes(StandardCharsets.UTF_8)));
        assertFalse(waiter.found());
        waiter.onData(ByteBuffer.wrap("fo\033[1;2;3mo".getBytes(StandardCharsets.UTF_8)));
        assertTrue(waiter.found());
    }
}