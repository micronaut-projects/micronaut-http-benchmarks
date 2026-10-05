package io.micronaut.benchmark.relay;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class TcpRelayTest {
    private static final Logger LOG = LoggerFactory.getLogger(TcpRelayTest.class);
    @AutoClose
    TcpRelay server = new TcpRelay().reestablishDelay(Duration.ZERO);
    @AutoClose
    TcpRelay client = new TcpRelay().reestablishDelay(Duration.ZERO);

    @AutoClose
    MockServer mockServer = new MockServer();

    @BeforeEach
    public void link() {
        InetSocketAddress serverAddress = server.bindTunnel("127.0.0.1", 0);
        client.linkTunnel(serverAddress);
    }

    private void kill() {
        Channel ch = client.currentClientChannel;
        if (ch != null) {
            ch.close();
        }
    }

    @Test
    public void simple() throws InterruptedException, ExecutionException {
        try (TcpRelay.Binding forward = client.bindForward(mockServer.address);
             MockClient cl = new MockClient(forward.address())) {

            cl.writeString("foo");
            assertEquals("foo", mockServer.connection(0).readString());
            mockServer.connection(0).writeString("bar");
            assertEquals("bar", cl.readString());
        }
    }

    @Test
    public void bufferedDataIsDeliveredWhenTunnelConnects() throws Exception {
        try (TcpRelay delayedClient = new TcpRelay().reestablishDelay(Duration.ZERO);
             TcpRelay.Binding forward = delayedClient.bindForward(mockServer.address);
             MockClient cl = new MockClient(forward.address())) {
            cl.writeString("foo");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (delayedClient.bufferedBytes() < 3 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(3, delayedClient.bufferedBytes());

            delayedClient.linkTunnel(server.bindTunnel("127.0.0.1", 0));

            assertNotNull(mockServer.connection(0).read(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void backAndForth() throws InterruptedException, ExecutionException {
        try (TcpRelay.Binding forward = client.bindForward(mockServer.address);
             MockClient cl = new MockClient(forward.address())) {

            for (int i = 0; i < 100; i++) {
                cl.writeString("foo" + i);
                assertEquals("foo" + i, mockServer.connection(0).readString());
                mockServer.connection(0).writeString("bar" + i);
                assertEquals("bar" + i, cl.readString());
            }
        }
    }

    @Test
    public void reestablish() throws InterruptedException, ExecutionException {
        try (TcpRelay.Binding forward = client.bindForward(mockServer.address);
             MockClient cl = new MockClient(forward.address())) {

            for (int i = 0; i < 100; i++) {
                cl.writeString("foo" + i);
                assertEquals("foo" + i, mockServer.connection(0).readString());
                mockServer.connection(0).writeString("bar" + i);
                assertEquals("bar" + i, cl.readString());

                kill();
            }
        }
    }

    @Test
    public void large() throws InterruptedException, ExecutionException {
        try (TcpRelay.Binding forward = client.bindForward(mockServer.address);
             MockClient cl = new MockClient(forward.address())) {

            CompositeByteBuf sent = Unpooled.compositeBuffer();
            for (int i = 0; i < 10; i++) {
                byte[] arr = new byte[10 * 1000 * 1000 + i * 3];
                ThreadLocalRandom.current().nextBytes(arr);
                cl.write(Unpooled.wrappedBuffer(arr));
                sent.addComponent(true, Unpooled.wrappedBuffer(arr));
                kill();
            }

            CompositeByteBuf received = ByteBufAllocator.DEFAULT.compositeBuffer();
            while (received.readableBytes() < sent.readableBytes()) {
                received.addComponent(true, mockServer.connection(0).read());
            }
            assertEquals(sent, received);
            received.release();
        }
    }

    @Test
    public void clientCloseIsRelayed() throws Exception {
        try (TcpRelay.Binding forward = client.bindForward(mockServer.address);
             MockClient cl = new MockClient(forward.address())) {

            cl.writeString("foo");
            assertEquals("foo", mockServer.connection(0).readString());
            cl.closeChannel();

            mockServer.connection(0).closed.get(5, TimeUnit.SECONDS);
            awaitNoStreams();
        }
    }

    @Test
    public void serverCloseIsRelayed() throws Exception {
        try (TcpRelay.Binding forward = client.bindForward(mockServer.address);
             MockClient cl = new MockClient(forward.address())) {

            cl.writeString("foo");
            assertEquals("foo", mockServer.connection(0).readString());
            mockServer.connection(0).writeString("bar");
            mockServer.connection(0).closeChannel();

            // data written before the close must still arrive
            assertEquals("bar", cl.readString());
            cl.closed.get(5, TimeUnit.SECONDS);
            awaitNoStreams();
        }
    }

    @Test
    public void closeIsRelayedAfterReconnect() throws Exception {
        try (TcpRelay.Binding forward = client.bindForward(mockServer.address);
             MockClient cl = new MockClient(forward.address())) {

            cl.writeString("foo");
            assertEquals("foo", mockServer.connection(0).readString());
            kill();
            cl.closeChannel();

            mockServer.connection(0).closed.get(5, TimeUnit.SECONDS);
            awaitNoStreams();
        }
    }

    @Test
    public void connectFailureClosesLocalSocket() throws Exception {
        InetSocketAddress unbound;
        try (ServerSocket tmp = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unbound = (InetSocketAddress) tmp.getLocalSocketAddress();
        }
        try (TcpRelay.Binding forward = client.bindForward(unbound);
             MockClient cl = new MockClient(forward.address())) {

            cl.closed.get(5, TimeUnit.SECONDS);
            assertNull(cl.read(0, TimeUnit.SECONDS));
            awaitNoStreams();
        }
    }

    private void awaitNoStreams() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((client.streamCount() != 0 || server.streamCount() != 0) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, client.streamCount());
        assertEquals(0, server.streamCount());
    }

    static class MockServer implements Closeable {
        private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        final InetSocketAddress address;
        private final List<MockConnection> connections = new ArrayList<>();

        MockServer() {
            ServerChannel channel = (ServerChannel) new ServerBootstrap()
                    .group(group)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            MockConnection c = new MockConnection();
                            ch.pipeline().addLast(c);
                            synchronized (MockServer.this) {
                                connections.add(c);
                                MockServer.this.notifyAll();
                            }
                        }
                    })
                    .channel(NioServerSocketChannel.class)
                    .bind("127.0.0.1", 0)
                    .syncUninterruptibly().channel();
            address = (InetSocketAddress) channel.localAddress();
        }

        synchronized MockConnection connection(int i) throws InterruptedException {
            while (connections.size() <= i) {
                wait();
            }
            return connections.get(i);
        }

        @Override
        public void close() {
            group.shutdownGracefully();
        }
    }

    static class MockConnection extends ChannelDuplexHandler {
        private final BlockingQueue<Object> inbound = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        ChannelHandlerContext ctx;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            this.ctx = ctx;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            inbound.put(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            closed.complete(null);
            super.channelInactive(ctx);
        }

        public void closeChannel() {
            ctx.close();
        }

        @SuppressWarnings("unchecked")
        public <E> E read() throws InterruptedException {
            return (E) inbound.take();
        }

        @SuppressWarnings("unchecked")
        public <E> E read(long timeout, TimeUnit unit) throws InterruptedException {
            return (E) inbound.poll(timeout, unit);
        }

        public String readString() throws InterruptedException {
            ByteBuf buf = read();
            String s = buf.toString(StandardCharsets.UTF_8);
            buf.release();
            LOG.trace("Reading: '{}'", s);
            return s;
        }

        public void write(Object obj) {
            ctx.writeAndFlush(obj, ctx.voidPromise());
        }

        public void writeString(String s) {
            LOG.trace("Writing: '{}'", s);
            write(Unpooled.copiedBuffer(s, StandardCharsets.UTF_8));
        }
    }

    static class MockClient extends MockConnection implements AutoCloseable {
        private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        MockClient(InetSocketAddress address) throws ExecutionException, InterruptedException {
            new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(this)
                    .connect(address).syncUninterruptibly().get();
        }

        @Override
        public void close() {
            group.close();
        }
    }
}
