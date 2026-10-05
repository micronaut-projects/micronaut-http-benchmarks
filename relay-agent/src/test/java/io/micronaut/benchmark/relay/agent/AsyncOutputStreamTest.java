package io.micronaut.benchmark.relay.agent;

import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    public void writesDoNotBlockAfterConsumerFails() throws IOException, InterruptedException {
        AsyncOutputStream async = new AsyncOutputStream(ByteBufAllocator.DEFAULT, new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("log reader gone");
            }
        }, 4);
        Thread consumer = Thread.ofVirtual().start(async);
        async.write(1);
        consumer.join();

        PrintStream ps = new PrintStream(async, true, StandardCharsets.UTF_8);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int i = 0; i < 100; i++) {
                ps.println("message " + i);
            }
        });
    }

    @Test
    public void droppedWritesAreReportedWhereTheyWereLost() throws IOException, InterruptedException {
        PipedInputStream pis = new PipedInputStream();
        PipedOutputStream pos = new PipedOutputStream(pis);
        AsyncOutputStream async = new AsyncOutputStream(ByteBufAllocator.DEFAULT, pos, 2);
        async.write('a');
        async.write('b');
        async.write('c'); // queue full, dropped

        Thread consumer = Thread.ofVirtual().start(async);
        assertEquals("ab", new String(pis.readNBytes(2), StandardCharsets.UTF_8));
        async.write('d');
        async.close();
        consumer.join();

        assertEquals("[1 log writes dropped]" + System.lineSeparator() + "d", new String(pis.readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    public void closeWithFullQueueClosesDestination() {
        boolean[] destinationClosed = {false};
        ByteArrayOutputStream destination = new ByteArrayOutputStream() {
            @Override
            public void close() {
                destinationClosed[0] = true;
            }
        };
        AsyncOutputStream async = new AsyncOutputStream(ByteBufAllocator.DEFAULT, destination, 2);
        async.write('a');
        async.write('b');
        async.close();

        assertTimeoutPreemptively(Duration.ofSeconds(10), async::run);
        assertEquals("ab", destination.toString(StandardCharsets.UTF_8));
        assertTrue(destinationClosed[0]);
    }
}
