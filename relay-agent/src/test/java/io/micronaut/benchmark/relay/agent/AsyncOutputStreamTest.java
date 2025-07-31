package io.micronaut.benchmark.relay.agent;

import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncOutputStreamTest {
    @Test
    public void test() throws IOException {
        PipedInputStream pis = new PipedInputStream();
        PipedOutputStream pos = new PipedOutputStream(pis);
        AsyncOutputStream async = new AsyncOutputStream(ByteBufAllocator.DEFAULT, pos, 128);
        Thread.ofVirtual().start(async);

        PrintStream ps = new PrintStream(async, true, StandardCharsets.UTF_8);
        ps.println("foo");
        ps.println("bar");
        ps.close();

        assertEquals("foo" + System.lineSeparator() + "bar" + System.lineSeparator(), new String(pis.readAllBytes(), StandardCharsets.UTF_8));
    }
}