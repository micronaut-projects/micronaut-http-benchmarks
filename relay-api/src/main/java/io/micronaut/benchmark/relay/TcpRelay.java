package io.micronaut.benchmark.relay;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.DefaultHttp2UnknownFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2PingFrame;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2SettingsAckFrame;
import io.netty.handler.codec.http2.Http2SettingsFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.handler.codec.http2.Http2UnknownFrame;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import java.io.Closeable;
import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class TcpRelay implements Closeable {
    private static final long TARGET_BUFFER_LENGTH = 4 * 1024 * 1024;
    private static final int WINDOW_SIZE = 16 * 1024 * 1024;

    private static final AsciiString HEADER_ERROR = AsciiString.of("error");
    private static final Logger LOG = LoggerFactory.getLogger(TcpRelay.class);

    private static final Http2Settings SETTINGS = new Http2Settings().initialWindowSize(WINDOW_SIZE);

    private final EventLoopGroup loop = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

    private final Map<String, StreamPair> streams = new HashMap<>();
    Channel currentClientChannel;

    private Duration reestablishDelay = Duration.ofSeconds(5);
    private Tls tls;

    public TcpRelay() {
    }

    public TcpRelay reestablishDelay(Duration reestablishDelay) {
        this.reestablishDelay = reestablishDelay;
        return this;
    }

    public TcpRelay tls(PrivateKey ourKey, X509Certificate ourCert, X509Certificate theirCert) {
        this.tls = new Tls(ourKey, ourCert, theirCert);
        return this;
    }

    public TcpRelay logBufferOccupancy(int interval, TimeUnit unit) {
        loop.scheduleWithFixedDelay(() -> {
            long total = 0;
            for (StreamPair pair : streams.values()) {
                total += pair.unreliableOutput.bufferLength;
            }
            LOG.info("Total buffer size: {}", total);
        }, interval, interval, unit);
        return this;
    }

    public InetSocketAddress bindTunnel(String host, int port) {
        InetSocketAddress address = ((ServerSocketChannel) new ServerBootstrap()
                .channel(NioServerSocketChannel.class)
                .group(loop)
                .childHandler(new ServerInitializer())
                .bind(host, port).syncUninterruptibly().channel()).localAddress();
        LOG.info("Bound tunnel to {}", address);
        return address;
    }

    public void linkTunnel(InetSocketAddress address) {
        linkOnce(address);
    }

    private void linkOnce(InetSocketAddress address) {
        LOG.info("Connecting to tunnel at {}", address);
        new Bootstrap()
                .channel(NioSocketChannel.class)
                .group(loop)
                .handler(new ClientInitializer())
                .connect(address);
    }

    public Binding bindForward(InetSocketAddress remoteAddress) {
        return new Binding((ServerSocketChannel) new ServerBootstrap()
                .channel(NioServerSocketChannel.class)
                .group(loop)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        Request request = new Request(
                                UUID.randomUUID().toString(),
                                remoteAddress.getHostString(),
                                remoteAddress.getPort(),
                                0, 0
                        );
                        LOG.info("Establishing new connection {}", request);
                        StreamPair pair = new StreamPair(
                                request,
                                new UnreliableInputStream(ch),
                                new UnreliableOutputStream(ch)
                        );
                        streams.put(request.id, pair);
                        ch.pipeline().addLast(new ReliableReceiver(pair.unreliableOutput));
                        tunnel(pair);
                    }
                })
                .bind("127.0.0.1", 0).syncUninterruptibly().channel());
    }

    private void tunnel(StreamPair pair) {
        if (currentClientChannel == null || !currentClientChannel.isActive()) {
            LOG.info("Holding back connection: {}", pair);
            return;
        }
        LOG.info("Opening stream for connection {}", pair);
        new Http2StreamChannelBootstrap(currentClientChannel)
                .handler(new ChannelInitializer<Http2StreamChannel>() {
                    @Override
                    protected void initChannel(Http2StreamChannel ch) {
                        long outputStart = pair.unreliableOutput.position - pair.unreliableOutput.bufferLength;
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                super.channelActive(ctx);

                                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new Request(
                                        pair.initialize.id,
                                        pair.initialize.host,
                                        pair.initialize.port,
                                        outputStart,
                                        pair.unreliableInput.position
                                ).toHttp2Headers(), false), ch.voidPromise());
                            }

                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                Http2HeadersFrame response = (Http2HeadersFrame) msg;
                                HttpResponseStatus status = HttpResponseStatus.parseLine(response.headers().status());
                                if (status == HttpResponseStatus.OK) {
                                    LOG.info("Stream for {} established", pair.initialize);
                                    ch.pipeline().remove(ctx.name());
                                    ch.pipeline()
                                            .addLast(new UnreliableSender(pair.unreliableOutput, outputStart))
                                            .addLast(new UnreliableReceiver(pair.unreliableInput, pair.unreliableInput.position));
                                } else {
                                    LOG.warn("Failed to connect stream ({}): {}", status, response.headers().get(HEADER_ERROR));
                                    ch.close();
                                }
                            }
                        });
                    }
                })
                .open().addListener(future -> {
                    if (!future.isSuccess()) {
                        // TODO
                        LOG.warn("Failed to open stream channel", future.cause());
                    }
                });
    }

    @Override
    public void close() {
        loop.shutdownGracefully();
    }

    private static boolean isDiscardable(Object msg) {
        return msg instanceof Http2SettingsFrame || msg instanceof Http2SettingsAckFrame || msg instanceof Http2GoAwayFrame || msg instanceof Http2PingFrame;
    }

    private class ClientInitializer extends ChannelInitializer<Channel> {
        @Override
        protected void initChannel(Channel ch) throws Exception {
            ch.config().setAutoRead(false);

            ch.pipeline().addLast(new IdleStateHandler(30, 0, 0) {
                @Override
                protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) {
                    if (ctx.channel().isOpen()) {
                        LOG.warn("Timeout reached, closing tunnel");
                        ctx.close();
                    }
                }
            });

            if (tls != null) {
                ch.pipeline().addLast(
                        SslContextBuilder.forClient()
                                .keyManager(tls.ourKey, List.of(tls.ourCert))
                                .trustManager(tls.theirCert)
                                .endpointIdentificationAlgorithm(null)
                                .build().newHandler(ch.alloc())
                );
            }

            ch.pipeline()
                    .addLast(Http2FrameCodecBuilder.forClient()
                            .autoAckPingFrame(true)
                            .autoAckSettingsFrame(true)
                            .initialSettings(SETTINGS)
                            .build())
                    .addLast(new Http2MultiplexHandler(new ChannelInitializer<Http2StreamChannel>() {
                        @Override
                        protected void initChannel(Http2StreamChannel ch) {
                        }
                    }))
                    .addLast(new ChannelInboundHandlerAdapter() {

                        private ScheduledFuture<?> pingFuture;

                        @Override
                        public void channelActive(ChannelHandlerContext ctx) throws Exception {
                            LOG.info("Connected to tunnel at {}", ch.remoteAddress());
                            super.channelActive(ctx);
                            currentClientChannel = ch;
                            for (StreamPair pair : streams.values()) {
                                tunnel(pair);
                            }
                            ctx.read();
                            pingFuture = ctx.executor().scheduleWithFixedDelay(
                                    () -> ctx.writeAndFlush(new DefaultHttp2PingFrame(ThreadLocalRandom.current().nextLong()), ctx.voidPromise()),
                                    1, 1, TimeUnit.SECONDS);
                        }

                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                            LOG.info("Tunnel became inactive, reestablishing");
                            pingFuture.cancel(false);
                            super.channelInactive(ctx);
                            loop.schedule(() -> linkOnce((InetSocketAddress) ctx.channel().remoteAddress()), reestablishDelay.toNanos(), TimeUnit.NANOSECONDS);
                        }

                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            if (isDiscardable(msg)) {
                                ctx.read();
                                return;
                            }
                            super.channelRead(ctx, msg);
                        }

                        @Override
                        public void handlerRemoved(ChannelHandlerContext ctx) {
                            if (currentClientChannel == ch) {
                                currentClientChannel = null;
                            }
                        }
                    });
        }
    }

    private class ServerInitializer extends ChannelInitializer<Channel> {
        @Override
        protected void initChannel(Channel ch) throws Exception {
            ch.config().setAutoRead(false);

            if (tls != null) {
                ch.pipeline().addLast(
                        SslContextBuilder.forServer(tls.ourKey, List.of(tls.ourCert))
                                .trustManager(tls.theirCert)
                                .clientAuth(ClientAuth.REQUIRE)
                                .build().newHandler(ch.alloc())
                );
            } else {
                LOG.info("New tunnel from {}", ch.remoteAddress());
            }

            ch.pipeline()
                    /*.addLast(PcapWriteHandler.builder()
                            .forceTcpChannel(
                                    new InetSocketAddress("127.0.0.1", 8082),
                                    new InetSocketAddress("127.0.0.1", 5556),
                                    true
                            )
                            .build(Files.newOutputStream(Path.of("/tmp/pcap"))))*/
                    .addLast(Http2FrameCodecBuilder.forServer()
                            .autoAckSettingsFrame(true)
                            .autoAckPingFrame(true)
                            .initialSettings(SETTINGS)
                            .build())
                    .addLast(new Http2MultiplexHandler(new ServerStreamInitializer()))
                    .addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) throws Exception {
                            super.channelActive(ctx);
                            ctx.read();
                        }

                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            if (isDiscardable(msg)) {
                                ctx.read();
                                return;
                            }
                            super.channelRead(ctx, msg);
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                            if (cause instanceof SSLHandshakeException || cause.getCause() instanceof SSLHandshakeException) {
                                LOG.debug("SSL error, closing connection", cause);
                                ctx.close();
                                return;
                            }
                            super.exceptionCaught(ctx, cause);
                        }

                        @Override
                        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                            if (evt instanceof SslHandshakeCompletionEvent c && c.isSuccess()) {
                                LOG.info("New tunnel from {}", ch.remoteAddress());
                            }
                        }
                    });
        }
    }

    private class ServerStreamInitializer extends ChannelInitializer<Http2StreamChannel> {
        @Override
        protected void initChannel(Http2StreamChannel ch) {
            ch.config().setAutoRead(false);
            ch.pipeline()
                    .addLast(new FlowControlHandler())
                    .addLast(new InitialServerStreamHandler());
        }
    }

    private class InitialServerStreamHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            ctx.read();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
            Request request = Request.fromHttp2Headers(headersFrame.headers());

            StreamPair streamPair = streams.get(request.id);
            if (streamPair == null) {
                LOG.info("Creating new forwarder: {}", request);
                new Bootstrap()
                        .channel(NioSocketChannel.class)
                        .group(loop)
                        .handler(new ChannelInitializer<>() {
                            @Override
                            protected void initChannel(Channel ch) {
                                StreamPair pair = streams.get(request.id);
                                if (pair != null) {
                                    // race
                                    ch.close();
                                    connect(ctx, request, pair);
                                    return;
                                }
                                pair = new StreamPair(
                                        request,
                                        new UnreliableInputStream(ch),
                                        new UnreliableOutputStream(ch)
                                );
                                ch.pipeline().addLast(new ReliableReceiver(pair.unreliableOutput));
                                streams.put(request.id, pair);
                                connect(ctx, request, pair);
                            }
                        })
                        .connect(request.host, request.port)
                        .addListener((ChannelFutureListener) future -> {
                            if (!future.isSuccess()) {
                                LOG.warn("Failed to connect to {}:{}", request.host, request.port, future.cause());
                                DefaultHttp2Headers response = new DefaultHttp2Headers();
                                response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR.codeAsText());
                                response.add(HEADER_ERROR, future.cause().toString());
                                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(response, true), ctx.voidPromise());
                            }
                        });
            } else {
                LOG.info("Linking existing forwarder: {}", request);
                connect(ctx, request, streamPair);
            }
        }

        private void connect(ChannelHandlerContext ctx, Request request, StreamPair streamPair) {
            DefaultHttp2Headers response = new DefaultHttp2Headers();
            response.status(HttpResponseStatus.OK.codeAsText());
            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(response, false), ctx.voidPromise());

            ctx.pipeline().addAfter(ctx.name(), null, new UnreliableReceiver(streamPair.unreliableInput, request.inputOffset));
            ctx.pipeline().addAfter(ctx.name(), null, new UnreliableSender(streamPair.unreliableOutput, request.outputOffset));
            ctx.pipeline().remove(ctx.name());

            ctx.read();
        }
    }

    private static final class UnreliableReceiver extends ChannelInboundHandlerAdapter {
        private final UnreliableInputStream stream;
        private long position;

        UnreliableReceiver(UnreliableInputStream stream, long position) {
            this.stream = stream;
            this.position = position;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2DataFrame data) {
                try {
                    ByteBuf buf = data.content();
                    if (this.position < stream.position) {
                        int skip = (int) Math.min(buf.readableBytes(), stream.position - this.position);
                        buf.skipBytes(skip);
                        this.position += skip;
                    }
                    if (buf.isReadable()) {
                        this.position += buf.readableBytes();
                        stream.position += buf.readableBytes();
                        stream.eof |= data.isEndStream();
                        stream.destination.writeAndFlush(buf.retain()).addListener(f -> ctx.read());
                        ctx.write(new DiscardUntilFrame(stream.position).toFrame(ctx.alloc()), ctx.voidPromise());
                    } else {
                        ctx.read();
                    }
                } finally {
                    data.release();
                }
            } else {
                ctx.fireChannelRead(msg);
            }
        }
    }

    private static final class ReliableReceiver extends ChannelInboundHandlerAdapter {
        private final UnreliableOutputStream stream;

        ReliableReceiver(UnreliableOutputStream stream) {
            this.stream = stream;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            ctx.channel().config().setAutoRead(false);
            ctx.read();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            if (!buf.isReadable()) {
                ctx.read();
                return;
            }
            stream.buffer.add(buf);
            stream.bufferLength += buf.readableBytes();
            stream.position += buf.readableBytes();
            LOG.trace("Received reliably: {} bytes, position={}, live={}", buf.readableBytes(), stream.position, stream.liveDestination);
            if (stream.liveDestination != null) {
                stream.liveDestination.writeAndFlush(buf.retainedSlice(), stream.liveDestination.voidPromise());
            }
            if (stream.bufferAvailable()) {
                ctx.read();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            stream.eof = true;
        }
    }

    private static final class UnreliableSender extends ChannelDuplexHandler {
        private final UnreliableOutputStream stream;
        private long position;

        UnreliableSender(UnreliableOutputStream stream, long position) {
            this.stream = stream;
            this.position = position;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            LOG.trace("Added unreliable sender");
            if (position < stream.position) {
                CompositeByteBuf catchup = ctx.alloc().compositeBuffer();
                long i = stream.position - stream.bufferLength;
                if (i > position) {
                    throw new IllegalStateException("Cannot catch up, not enough data buffered");
                }
                for (ByteBuf buf : stream.buffer) {
                    ByteBuf slice = buf.retainedSlice();
                    if (i < position) {
                        int n = (int) Math.min(buf.readableBytes(), position - i);
                        i += n;
                        slice.skipBytes(n);
                    }
                    if (slice.isReadable()) {
                        catchup.addComponent(true, slice);
                    } else {
                        slice.release();
                    }
                }
                LOG.trace("Replaying {}", catchup);
                write(ctx, catchup, ctx.voidPromise());
            }
            stream.liveDestination = ctx.channel();
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            if (stream.liveDestination == ctx.channel()) {
                stream.liveDestination = null;
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Http2UnknownFrame u) {
                DiscardUntilFrame discardUntilFrame = DiscardUntilFrame.fromUnknownFrame(u);
                if (discardUntilFrame != null) {
                    stream.pruneUntil(discardUntilFrame.untilPosition);
                    ctx.read();
                    return;
                }
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof Http2UnknownFrame u && u.frameType() == DiscardUntilFrame.FRAME_TYPE) {
                super.write(ctx, msg, promise);
                return;
            }

            ByteBuf buf = (ByteBuf) msg;
            position += buf.readableBytes();
            ctx.write(new DefaultHttp2DataFrame(buf), promise);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            super.userEventTriggered(ctx, evt);
        }
    }

    private sealed abstract static class Stream {
        long position = 0;
        boolean eof;
    }

    private static final class UnreliableInputStream extends Stream {
        final Channel destination;

        UnreliableInputStream(Channel destination) {
            this.destination = destination;
        }
    }

    private static final class UnreliableOutputStream extends Stream {
        final Queue<ByteBuf> buffer = new ArrayDeque<>();
        long bufferLength = 0;
        final Channel source;
        Channel liveDestination;

        UnreliableOutputStream(Channel source) {
            this.source = source;
        }

        boolean bufferAvailable() {
            return bufferLength < TARGET_BUFFER_LENGTH;
        }

        void pruneUntil(long until) {
            LOG.trace("Pruning until {}", until);
            boolean hadBufferAvailable = bufferAvailable();
            while (true) {
                ByteBuf first = buffer.peek();
                if (first == null) {
                    break;
                }
                long end = position - bufferLength + first.readableBytes();
                if (end <= until) {
                    bufferLength -= first.readableBytes();
                    first.release();
                    buffer.poll();
                } else {
                    break;
                }
            }
            if (!hadBufferAvailable && bufferAvailable()) {
                source.read();
            }
        }
    }

    private record StreamPair(
            Request initialize,
            UnreliableInputStream unreliableInput,
            UnreliableOutputStream unreliableOutput
    ) {
    }

    private record Request(
            String id,
            String host,
            int port,
            long inputOffset,
            long outputOffset
    ) {
        private static final AsciiString HEADER_HOST = AsciiString.of("host");
        private static final AsciiString HEADER_PORT = AsciiString.of("port");
        private static final AsciiString HEADER_INPUT_OFFSET = AsciiString.of("input-offset");
        private static final AsciiString HEADER_OUTPUT_OFFSET = AsciiString.of("output-offset");

        static Request fromHttp2Headers(Http2Headers headers) {
            return new Request(
                    headers.path().toString(),
                    headers.get(HEADER_HOST).toString(),
                    headers.getInt(HEADER_PORT),
                    headers.getLong(HEADER_INPUT_OFFSET, 0),
                    headers.getLong(HEADER_OUTPUT_OFFSET, 0)
            );
        }

        Http2Headers toHttp2Headers() {
            return new DefaultHttp2Headers()
                    .path(id)
                    .add(HEADER_HOST, host)
                    .addInt(HEADER_PORT, port)
                    .addLong(HEADER_INPUT_OFFSET, inputOffset)
                    .addLong(HEADER_OUTPUT_OFFSET, outputOffset);
        }
    }

    public static final class Binding implements Closeable {
        private final ServerSocketChannel channel;

        private Binding(ServerSocketChannel channel) {
            this.channel = channel;
        }

        public InetSocketAddress address() {
            return channel.localAddress();
        }

        @Override
        public void close() {
            channel.close();
        }
    }

    private record Tls(
            PrivateKey ourKey,
            X509Certificate ourCert,
            X509Certificate theirCert
    ) {
    }

    private record DiscardUntilFrame(
            long untilPosition
    ) {
        private static final byte FRAME_TYPE = 0x71;

        static DiscardUntilFrame fromUnknownFrame(Http2UnknownFrame frame) {
            if (frame.frameType() != FRAME_TYPE) {
                return null;
            }
            try {
                long l = frame.content().readLong();
                return new DiscardUntilFrame(l);
            } finally {
                frame.release();
            }
        }

        Http2StreamFrame toFrame(ByteBufAllocator alloc) {
            ByteBuf buffer = alloc.buffer(8);
            buffer.writeLong(untilPosition);
            return new DefaultHttp2UnknownFrame(FRAME_TYPE, new Http2Flags(), buffer);
        }
    }
}
