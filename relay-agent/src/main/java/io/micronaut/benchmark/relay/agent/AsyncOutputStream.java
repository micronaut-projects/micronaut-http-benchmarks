package io.micronaut.benchmark.relay.agent;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

final class AsyncOutputStream extends OutputStream implements Runnable {
    private static final Object FLUSH = new Object();
    private static final Object CLOSE = new Object();

    private final ByteBufAllocator allocator;
    private final OutputStream destination;
    private final BlockingQueue<Object> queue;

    public AsyncOutputStream(ByteBufAllocator allocator, OutputStream destination, int queueDepth) {
        this.allocator = allocator;
        this.destination = destination;
        this.queue = new ArrayBlockingQueue<>(queueDepth);
    }

    @Override
    public void write(int b) throws IOException {
        ByteBuf buf = allocator.heapBuffer(1);
        buf.writeByte(b);
        write(buf);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ByteBuf buf = allocator.heapBuffer(len);
        buf.writeBytes(b, off, len);
        write(buf);
    }

    private void write(Object buf) throws IOException {
        try {
            queue.put(buf);
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }

    @Override
    public void flush() throws IOException {
        write(FLUSH);
    }

    @Override
    public void close() throws IOException {
        write(CLOSE);
    }

    @Override
    public void run() {
        try {
            while (true) {
                Object obj = queue.take();
                if (obj == FLUSH) {
                    destination.flush();
                } else if (obj == CLOSE) {
                    destination.close();
                    break;
                } else {
                    ByteBuf buf = (ByteBuf) obj;
                    try {
                        buf.readBytes(destination, buf.readableBytes());
                    } finally {
                        buf.release();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
