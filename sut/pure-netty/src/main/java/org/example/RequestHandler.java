package org.example;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.ReadTimeoutException;

import java.io.IOException;
import java.net.URI;

@ChannelHandler.Sharable
public class RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    static final RequestHandler INSTANCE = new RequestHandler();

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().config().setAutoRead(false);
        ctx.read();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof ReadTimeoutException) {
            // ignore
            ctx.close();
        } else {
            super.exceptionCaught(ctx, cause);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        FullHttpResponse response = computeResponse(ctx, msg);
        response.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response, ctx.voidPromise());
        ctx.read();
    }

    private FullHttpResponse computeResponse(ChannelHandlerContext ctx, FullHttpRequest msg) {
        try {
            String path = URI.create(msg.uri()).getPath();
            if (path.equals("/search/find")) {
                return computeResponseSearch(ctx, msg);
            }
            if (path.equals("/status")) {
                return computeResponseStatus(ctx, msg);
            }
            return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
        } catch (Exception e) {
            e.printStackTrace();
            return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private FullHttpResponse computeResponseSearch(ChannelHandlerContext ctx, FullHttpRequest msg) throws IOException {
        if (!msg.method().equals(HttpMethod.POST)) {
            return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.METHOD_NOT_ALLOWED);
        }
        if (!msg.headers().contains(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON, true)) {
            return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE);
        }

        ByteBuf res = AgnosticRequestHandler.INSTANCE.find(ctx, msg.content());
        return res == null ? new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND) : ok(res);
    }

    private FullHttpResponse computeResponseStatus(ChannelHandlerContext ctx, FullHttpRequest msg) throws IOException {
        if (!msg.method().equals(HttpMethod.GET)) {
            return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.METHOD_NOT_ALLOWED);
        }

        return ok(AgnosticRequestHandler.INSTANCE.status(ctx));
    }

    private FullHttpResponse ok(ByteBuf buffer) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buffer);
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
        //response.headers().add(HttpHeaderNames.CONTENT_LENGTH, buffer.readableBytes());
        return response;
    }
}
