package org.example;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.Http2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;

import javax.net.ssl.SSLException;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class HttpServer implements AutoCloseable {
    private static final Http2Settings INITIAL_H2_SETTINGS = Http2Settings.defaultSettings();

    private final ServerBootstrap tcpBootstrap;
    private final EventLoopGroup group;
    private final ConnectionErrorHandler connectionErrors = new ConnectionErrorHandler();

    public HttpServer() {
        group = new MultiThreadIoEventLoopGroup(Runtime.getRuntime().availableProcessors(), IoUringIoHandler.newFactory());
        group.terminationFuture().addListener(ignored -> connectionErrors.reportSuppressed());
        tcpBootstrap = new ServerBootstrap()
                .channel(IoUringServerSocketChannel.class)
                .group(group)
                .option(ChannelOption.SO_BACKLOG, Integer.MAX_VALUE)
                .childOption(ChannelOption.AUTO_READ, true);
    }

    public InetSocketAddress bindHttp(String host, int port) {
        Channel channel = tcpBootstrap.clone()
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new ReadTimeoutHandler(1, TimeUnit.MINUTES));
                        addHttp1Handlers(ch.pipeline());
                    }
                })
                .bind(host, port).syncUninterruptibly().channel();
        return ((ServerSocketChannel) channel).localAddress();
    }

    public InetSocketAddress bindHttps(String host, int port) throws SSLException {
        String tlsDirectory = System.getProperty("benchmark.tls.directory", "/etc/benchmark-tls");
        SslContext sslContext = SslContextBuilder.forServer(
                        new File(tlsDirectory, "server.pem"),
                        new File(tlsDirectory, "server-key.pem"))
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2,
                        ApplicationProtocolNames.HTTP_1_1
                ))
                .ciphers(List.of("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_AES_128_GCM_SHA256"))
                .build();
        Channel channel = tcpBootstrap.clone()
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline()
                                .addLast(new ReadTimeoutHandler(1, TimeUnit.MINUTES))
                                .addLast(new SslHandler(sslContext.newEngine(ch.alloc())))
                                .addLast(new ApplicationProtocolNegotiationHandler("http/1.1") {
                                    @Override
                                    protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
                                        if (protocol.equals("http/1.1")) {
                                            addHttp1Handlers(ctx.pipeline());
                                        } else if (protocol.equals("h2")) {
                                            addHttp2Handlers(ctx.pipeline());
                                        }
                                    }
                                });
                    }
                })
                .bind(host, port).syncUninterruptibly().channel();
        return ((ServerSocketChannel) channel).localAddress();
    }

    private void addHttp1Handlers(ChannelPipeline pipeline) {
        pipeline.addLast(new HttpServerCodec())
                .addLast(makeAggregator())
                .addLast(RequestHandler.INSTANCE)
                .addLast(connectionErrors);
    }

    private void addHttp2Handlers(ChannelPipeline pipeline) {
        pipeline.addLast(new Http2ConnectionHandlerBuilder()
                        .server(true)
                        .validateHeaders(true)
                        .initialSettings(INITIAL_H2_SETTINGS)
                        .frameListener(new RequestHandlerHttp2Frame())
                        .build())
                .addLast(connectionErrors);
    }

    private static HttpObjectAggregator makeAggregator() {
        HttpObjectAggregator aggregator = new HttpObjectAggregator(10_000_000);
        aggregator.setMaxCumulationBufferComponents(100000);
        return aggregator;
    }

    @Override
    public void close() {
        group.shutdownGracefully();
    }
}
