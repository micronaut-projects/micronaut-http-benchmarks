package io.micronaut.benchmark.relay.agent;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Output stream that hands writes off to a consumer thread ({@link #run()}). Writers never block: this stream is
 * installed as {@code System.err}, so logging from the relay event loop must not stall when the destination is slow
 * or gone. Writes that don't fit in the queue, or that arrive after the consumer has failed, are dropped.
 */
final class AsyncOutputStream extends OutputStream implements Runnable {
    private static final Object FLUSH = new Object();
    /**
     * Only wakes the consumer up so that it notices {@link #closed}.
     */
    private static final Object WAKE = new Object();

    private final ByteBufAllocator allocator;
    private final OutputStream destination;
    private final BlockingQueue<Object> queue;
    /**
     * Writes dropped because the queue was full, not yet reported in the queue.
     */
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean closed;
    private volatile boolean dead;

    public AsyncOutputStream(ByteBufAllocator allocator, OutputStream destination, int queueDepth) {
        this.allocator = allocator;
        this.destination = destination;
        this.queue = new ArrayBlockingQueue<>(queueDepth);
    }

    @Override
    public void write(int b) {
        if (closed || dead) {
            return;
        }
        ByteBuf buf = allocator.heapBuffer(1);
        buf.writeByte(b);
        write(buf);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        if (closed || dead) {
            return;
        }
        ByteBuf buf = allocator.heapBuffer(len);
        buf.writeBytes(b, off, len);
        write(buf);
    }

    private void write(Object obj) {
        if (closed || dead) {
            ReferenceCountUtil.release(obj);
            return;
        }
        // Report earlier drops in the queue right before this write, so the report lands where the gap is.
        long droppedBefore = dropped.getAndSet(0);
        if ((droppedBefore != 0 && !queue.offer(new Dropped(droppedBefore))) || !queue.offer(obj)) {
            ReferenceCountUtil.release(obj);
            dropped.addAndGet(droppedBefore + 1);
        } else if (dead) {
            // the consumer may have died between the check and the offer
            drain();
        }
    }

    @Override
    public void flush() {
        write(FLUSH);
    }

    /**
     * Stop accepting writes. The consumer closes the destination once it has written everything queued before this
     * call.
     */
    @Override
    public void close() {
        closed = true;
        // If the queue is full, the consumer is not blocked and will see the flag once the queue is empty.
        queue.offer(WAKE);
    }

    @Override
    public void run() {
        try {
            while (true) {
                Object obj = queue.poll();
                if (obj == null) {
                    if (closed) {
                        destination.close();
                        break;
                    }
                    obj = queue.take();
                }
                if (obj == FLUSH) {
                    destination.flush();
                } else if (obj instanceof Dropped(long count)) {
                    destination.write(("[" + count + " log writes dropped]" + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                } else if (obj instanceof ByteBuf buf) {
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
        } finally {
            dead = true;
            drain();
        }
    }

    private void drain() {
        Object obj;
        while ((obj = queue.poll()) != null) {
            ReferenceCountUtil.release(obj);
        }
    }

    private record Dropped(long count) {
    }
}
