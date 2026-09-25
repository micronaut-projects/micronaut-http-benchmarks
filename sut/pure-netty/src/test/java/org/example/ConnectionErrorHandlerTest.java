package org.example;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.unix.Errors;
import io.netty.channel.uring.IoUring;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionErrorHandlerTest {
    @Test
    void summarizesDisconnectStormAcrossConnectionsEvenAfterTrafficStops() {
        Logger logger = (Logger) LoggerFactory.getLogger(ConnectionErrorHandler.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        var handler = new ConnectionErrorHandler();
        var first = new EmbeddedChannel(handler);
        var second = new EmbeddedChannel(handler);
        try {
            var cause = new ClosedChannelException();
            first.pipeline().fireExceptionCaught(cause);
            for (int i = 0; i < 1_000; i++) {
                second.pipeline().fireExceptionCaught(new ClosedChannelException());
            }
            assertEquals(1, events.list.size());
            assertSame(cause, ((ThrowableProxy) events.list.getFirst().getThrowableProxy()).getThrowable());
            assertTrue(events.list.getFirst().getFormattedMessage().contains(first.id().asShortText()));
            assertTrue(first.isOpen());
            assertTrue(second.isOpen());

            // An unrelated failure must not disappear behind the disconnect throttle.
            var unexpected = new IllegalStateException("Unexpected failure", cause);
            second.pipeline().fireExceptionCaught(unexpected);
            assertEquals(2, events.list.size());
            assertSame(unexpected, ((ThrowableProxy) events.list.get(1).getThrowableProxy()).getThrowable());

            first.advanceTimeBy(10, TimeUnit.SECONDS);
            first.runScheduledPendingTasks();
            assertEquals(3, events.list.size());
            assertTrue(events.list.get(2).getFormattedMessage().contains("Suppressed 1000 additional disconnect log events"));
            assertNull(events.list.get(2).getThrowableProxy());

            second.pipeline().fireExceptionCaught(new ClosedChannelException());
            assertEquals(4, events.list.size());
            assertNotNull(events.list.get(3).getThrowableProxy());
        } finally {
            handler.reportSuppressed();
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void throttlesBrokenPipesAndResetsButRetainsOtherIoErrors() {
        // Load the transport JNI before constructing native I/O exceptions.
        IoUring.isAvailable();
        Logger logger = (Logger) LoggerFactory.getLogger(ConnectionErrorHandler.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        var handler = new ConnectionErrorHandler();
        var channel = new EmbeddedChannel(handler);
        try {
            channel.pipeline().fireExceptionCaught(new Errors.NativeIoException("write", Errors.ERRNO_EPIPE_NEGATIVE));
            channel.pipeline().fireExceptionCaught(new Errors.NativeIoException("write", Errors.ERRNO_ECONNRESET_NEGATIVE));
            assertEquals(1, events.list.size());
            channel.pipeline().fireExceptionCaught(new java.io.IOException("Unexpected I/O failure"));
            assertEquals(2, events.list.size());
            handler.reportSuppressed();
            assertEquals(3, events.list.size());
            assertTrue(events.list.getLast().getFormattedMessage().contains("Suppressed 1 additional disconnect log events"));
        } finally {
            handler.reportSuppressed();
            channel.finishAndReleaseAll();
            logger.detachAppender(events);
            events.stop();
        }
    }
}
