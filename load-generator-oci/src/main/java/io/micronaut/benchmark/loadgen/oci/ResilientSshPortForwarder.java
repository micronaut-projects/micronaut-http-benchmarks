package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.relay.CommandRunner;
import io.micronaut.benchmark.relay.SshCommandRunner;
import io.micronaut.scheduling.TaskExecutors;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * SSH port forwarder that re-establishes the forwarded connection if it dies.
 */
public final class ResilientSshPortForwarder implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(ResilientSshPortForwarder.class);

    private final EventLoop loop;
    private final Supplier<TransientPortForwarder> sessionFactory;
    private final Bootstrap upstreamBootstrap;
    private TransientPortForwarder current;
    private ServerSocketChannel downstreamServerChannel;

    ResilientSshPortForwarder(EventLoop loop, Supplier<TransientPortForwarder> sessionFactory) {
        this.loop = loop;
        this.sessionFactory = sessionFactory;
        this.upstreamBootstrap = new Bootstrap()
                .channel(NioSocketChannel.class)
                .option(ChannelOption.AUTO_READ, false)
                .group(loop);
    }

    private <T> Future<T> retry(Supplier<Future<T>> attempt, int tries) {
        assert loop.inEventLoop();
        Future<T> boundAddressFuture = attempt.get();
        if (tries <= 0) {
            return boundAddressFuture;
        } else {
            Promise<T> guardedPromise = loop.newPromise();
            boundAddressFuture.addListener((GenericFutureListener<Future<T>>) f -> {
                if (f.isSuccess()) {
                    guardedPromise.setSuccess(f.get());
                } else {
                    loop.schedule(() -> {
                        retry(attempt, tries - 1).addListener((GenericFutureListener<Future<T>>) g -> {
                            if (g.isSuccess()) {
                                guardedPromise.setSuccess(g.get());
                            } else {
                                guardedPromise.setFailure(g.cause());
                            }
                        });
                    }, 10, TimeUnit.SECONDS);
                }
            });
            return guardedPromise;
        }
    }

    private Promise<Channel> connectOnce(ChannelHandler initialHandler) {
        assert loop.inEventLoop();
        if (current == null) {
            current = sessionFactory.get();
        }
        Promise<Channel> connectPromise = loop.newPromise();
        TransientPortForwarder transientForwarder = current;
        transientForwarder.boundAddress().addListener((GenericFutureListener<Future<InetSocketAddress>>) addressFuture -> {
            if (addressFuture.isSuccess()) {
                upstreamBootstrap.clone()
                        .handler(initialHandler)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000) // this is a loopback connection
                        .connect(addressFuture.get())
                        .addListener((ChannelFutureListener) upstreamFuture -> {
                            if (upstreamFuture.isSuccess()) {
                                connectPromise.setSuccess(upstreamFuture.channel());
                            } else {
                                if (current == transientForwarder) {
                                    current = null;
                                    transientForwarder.close();
                                }
                                connectPromise.setFailure(upstreamFuture.cause());
                            }
                        });
            } else {
                if (current == transientForwarder) {
                    current = null;
                    transientForwarder.close();
                }
                connectPromise.setFailure(addressFuture.cause());
            }
        });
        return connectPromise;
    }

    public void disconnect() {
        try {
            loop.submit(() -> {
                if (current != null) {
                    try {
                        current.close();
                    } catch (IOException e) {
                        LOG.warn("Failed to close connection", e);
                    }
                    current = null;
                }
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        disconnect();
        downstreamServerChannel.close().syncUninterruptibly();
    }

    @ChannelHandler.Sharable
    private static final class ForwardHandler extends ChannelInboundHandlerAdapter {
        private final Channel other;

        ForwardHandler(Channel other) {
            this.other = other;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            ctx.read();
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);
            ctx.read();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            other.writeAndFlush(msg).addListener(future -> {
                if (future.isSuccess()) {
                    ctx.read();
                } else {
                    LOG.warn("Exception on SSH pipeline", future.cause());
                    ctx.close();
                }
            });
        }
    }

    private final class NewConnectionInitializer extends ChannelInitializer<Channel> {
        @Override
        protected void initChannel(Channel downstream) throws Exception {
            ForwardHandler toDownstream = new ForwardHandler(downstream);
            retry(() -> connectOnce(toDownstream), 5).addListener((GenericFutureListener<Future<Channel>>) future -> {
                if (future.isSuccess()) {
                    Channel upstream = future.get();
                    linkClose(upstream, downstream);
                    linkClose(downstream, upstream);
                    downstream.pipeline().addLast(new ForwardHandler(upstream));
                } else {
                    LOG.warn("Failed to open resilient connection", future.cause());
                    downstream.close();
                }
            });
        }
    }

    InetSocketAddress bind() {
        downstreamServerChannel = (ServerSocketChannel) new ServerBootstrap()
                .group(loop)
                .channel(NioServerSocketChannel.class)
                .childHandler(new NewConnectionInitializer())
                .childOption(ChannelOption.AUTO_READ, false)
                .bind(InetAddress.getLoopbackAddress(), 0).syncUninterruptibly().channel();
        return address();
    }

    InetSocketAddress address() {
        return downstreamServerChannel.localAddress();
    }

    private static void linkClose(Channel to, Channel from) {
        from.closeFuture().addListener((ChannelFutureListener) future -> to.close());
    }

    interface TransientPortForwarder extends Closeable {
        Future<InetSocketAddress> boundAddress();
    }

    @Singleton
    public static final class Factory {
        private final EventLoop loop;
        private final Executor blocking;

        public Factory(@Named(TaskExecutors.IO) Executor blocking) {
            this.blocking = blocking;
            this.loop = new NioEventLoopGroup(1).next();
        }

        public ResilientSshPortForwarder create(Callable<CommandRunner> sessionCallable, SshdSocketAddress remote) {
            ResilientSshPortForwarder resilientForwarder = new ResilientSshPortForwarder(loop, () -> {
                SshPortForwarder forwarder = new SshPortForwarder(loop.newPromise(), loop.newPromise());
                blocking.execute(() -> forwarder.connectBlocking(sessionCallable, remote));
                return forwarder;
            });
            resilientForwarder.bind();
            return resilientForwarder;
        }
    }

    private record SshPortForwarder(
            Promise<InetSocketAddress> boundAddress,
            Promise<Void> closeFuture
    ) implements TransientPortForwarder {

        void connectBlocking(Callable<CommandRunner> sessionCallable, SshdSocketAddress remote) {
            try (CommandRunner clientSession = sessionCallable.call();
                 // TODO
                 ExplicitPortForwardingTracker forwardingTracker = ((SshCommandRunner) clientSession).getSession().createLocalPortForwardingTracker(new SshdSocketAddress("localhost", 0), remote)) {
                boundAddress.setSuccess(new InetSocketAddress(forwardingTracker.getBoundAddress().getHostName(), forwardingTracker.getBoundAddress().getPort()));
                closeFuture.await();
            } catch (Exception e) {
                boundAddress.tryFailure(e);
                closeFuture.tryFailure(e);
            }
        }

        @Override
        public void close() {
            closeFuture.trySuccess(null);
        }
    }
}
