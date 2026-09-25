package org.example;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.unix.Errors;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeUnit;

/** Keep disconnect storms from logging a stack trace for every pending response. */
@ChannelHandler.Sharable
final class ConnectionErrorHandler extends ChannelInboundHandlerAdapter {
    private static final InternalLogger LOG = InternalLoggerFactory.getInstance(ConnectionErrorHandler.class);

    private ScheduledFuture<?> summary;
    private long suppressed;

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (isDisconnect(cause)) {
            logDisconnect(ctx, cause);
        } else {
            LOG.warn("Unhandled channel exception on " + ctx.channel(), cause);
        }
    }

    private synchronized void logDisconnect(ChannelHandlerContext ctx, Throwable cause) {
        if (summary != null) {
            suppressed++;
            return;
        }
        LOG.warn("Connection error on " + ctx.channel() + "; further disconnects will be summarized for 10 seconds", cause);
        summary = ctx.executor().schedule(this::reportSuppressed, 10, TimeUnit.SECONDS);
    }

    synchronized void reportSuppressed() {
        if (suppressed != 0) {
            LOG.warn("Suppressed {} additional disconnect log events across server connections", suppressed);
            suppressed = 0;
        }
        if (summary != null) {
            summary.cancel(false);
            summary = null;
        }
    }

    private static boolean isDisconnect(Throwable cause) {
        return cause instanceof ClosedChannelException
                || cause instanceof Errors.NativeIoException nativeError
                && (nativeError.expectedErr() == Errors.ERRNO_EPIPE_NEGATIVE
                || nativeError.expectedErr() == Errors.ERRNO_ECONNRESET_NEGATIVE);
    }
}
