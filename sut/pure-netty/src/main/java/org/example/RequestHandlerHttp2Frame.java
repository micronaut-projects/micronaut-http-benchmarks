package org.example;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.AsciiString;

import java.io.IOException;

final class RequestHandlerHttp2Frame extends Http2FrameAdapter {
    static final AsciiString PATH_STATUS = AsciiString.of("/status");
    static final AsciiString PATH_FIND = AsciiString.of("/search/find");

    private Http2ConnectionHandler connectionHandler;
    private Http2Connection.PropertyKey holderPropertyKey;

    public RequestHandlerHttp2Frame() {
    }

    private StreamHolder stream(ChannelHandlerContext ctx, int streamId) {
        if (connectionHandler == null) {
            connectionHandler = ctx.pipeline().get(Http2ConnectionHandler.class);
            holderPropertyKey = connectionHandler.connection().newKey();
        }
        Http2Stream stream = connectionHandler.connection().stream(streamId);
        StreamHolder existing = stream.getProperty(holderPropertyKey);
        if (existing == null) {
            existing = new StreamHolder(ctx, streamId);
            stream.setProperty(holderPropertyKey, existing);
        }
        return existing;
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding, boolean endStream) throws Http2Exception {
        StreamHolder stream = stream(ctx, streamId);
        stream.onHeadersRead(headers);
        if (endStream) {
            stream.eof();
        }
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endStream) throws Http2Exception {
        StreamHolder stream = stream(ctx, streamId);
        stream.onHeadersRead(headers);
        if (endStream) {
            stream.eof();
        }
    }

    @Override
    public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) throws Http2Exception {
        int res = super.onDataRead(ctx, streamId, data, padding, endOfStream); // flow control value
        StreamHolder stream = stream(ctx, streamId);
        stream.onDataRead(data.retain());
        if (endOfStream) {
            stream.eof();
        }
        return res;
    }

    private class StreamHolder {
        private final ChannelHandlerContext ctx;
        private final int streamId;
        boolean find = false;
        ByteBuf buf = null;

        StreamHolder(ChannelHandlerContext ctx, int streamId) {
            this.ctx = ctx;
            this.streamId = streamId;
        }

        void onHeadersRead(Http2Headers headers) {
            if (PATH_FIND.equals(headers.path())) {
                if (!HttpMethod.POST.asciiName().contentEqualsIgnoreCase(headers.method())) {
                    error(HttpResponseStatus.METHOD_NOT_ALLOWED);
                    return;
                }
                if (!headers.contains(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON, true)) {
                    error(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE);
                    return;
                }

                find = true;
                return;
            }
            if (PATH_STATUS.equals(headers.path())) {
                if (!HttpMethod.GET.asciiName().contentEqualsIgnoreCase(headers.method())) {
                    error(HttpResponseStatus.METHOD_NOT_ALLOWED);
                    return;
                }

                ByteBuf buf;
                try {
                    buf = AgnosticRequestHandler.INSTANCE.status(ctx);
                } catch (IOException e) {
                    e.printStackTrace();
                    error(HttpResponseStatus.INTERNAL_SERVER_ERROR);
                    return;
                }
                ok(buf);
                return;
            }

            error(HttpResponseStatus.NOT_FOUND);
        }

        private void error(HttpResponseStatus status) {
            DefaultHttp2Headers responseHeaders = new DefaultHttp2Headers();
            responseHeaders.status(status.codeAsText());
            connectionHandler.encoder().writeHeaders(ctx, streamId, responseHeaders, 0, true, ctx.voidPromise());
        }

        private void ok(ByteBuf buf) {
            DefaultHttp2Headers responseHeaders = new DefaultHttp2Headers();
            responseHeaders.status(HttpResponseStatus.OK.codeAsText());
            responseHeaders.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
            connectionHandler.encoder().writeHeaders(ctx, streamId, responseHeaders, 0, false, ctx.voidPromise());
            connectionHandler.encoder().writeData(ctx, streamId, buf, 0, true, ctx.voidPromise());
        }

        void onDataRead(ByteBuf data) {
            if (find) {
                if (buf == null) {
                    buf = data;
                } else if (buf instanceof CompositeByteBuf comp) {
                    comp.addComponent(true, data);
                } else {
                    CompositeByteBuf comp = ctx.alloc().compositeBuffer();
                    comp.addComponent(true, buf);
                    comp.addComponent(true, data);
                    buf = comp;
                }
            } else {
                data.release();
            }
        }

        void eof() {
            if (find) {
                if (buf == null) {
                    buf = Unpooled.EMPTY_BUFFER;
                }
                ByteBuf res;
                try {
                    res = AgnosticRequestHandler.INSTANCE.find(ctx, buf);
                } catch (IOException e) {
                    e.printStackTrace();
                    error(HttpResponseStatus.INTERNAL_SERVER_ERROR);
                    return;
                } finally {
                    buf.release();
                }
                ok(res);
            }
        }
    }
}
