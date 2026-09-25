package io.micronaut.benchmark.loadgen.oci;

import com.sun.net.httpserver.HttpServer;
import io.hyperfoil.http.statistics.HttpStats;
import io.micronaut.benchmark.api.BenchmarkStats;
import io.micronaut.benchmark.api.ThroughputSearch;
import io.micronaut.benchmark.api.ThroughputStage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the exact Nix-pinned distribution, not the Maven client version or mocked SLA evaluation. */
@Tag("hyperfoil")
class RealHyperfoilThroughputTest {
    @TempDir Path temporary;
    static final JsonMapper JSON = JsonMapper.builder().registerSubtypes(HttpStats.class).build();

    @Test
    void nativeSlaPercentilesUseConfiguredFractions() throws Exception {
        String home = System.getenv("HYPERFOIL_HOME");
        assertNotNull(home, "Set HYPERFOIL_HOME to the output of nix/system/hyperfoil.nix");
        Path source = temporary.resolve("PercentileSlaCheck.java");
        try (var input = getClass().getResourceAsStream("/hyperfoil/PercentileSlaCheck.java")) {
            Files.copy(input, source);
        }
        String classpath;
        try (var files = Files.walk(Path.of(home, "lib"))) {
            classpath = files.filter(p -> p.toString().endsWith(".jar")).map(Path::toString)
                    .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        }
        Path log = temporary.resolve("percentile-sla.log");
        // Only evaluates synthetic histograms in the pinned distribution; generates no traffic.
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx128m", "-cp", classpath, source.toString())
                .directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "SLA check timed out; " + log);
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String phase(String name, int rate, String previous, String limit) {
        return """
                - %s:
                    constantRate:
                      duration: 2s
                      usersPerSec: %d
                      maxSessions: 256
                      sessionLimitPolicy: FAIL
                      %s
                      scenario:
                      - test:
                        - httpRequest:
                            GET: /
                            sla:
                            - limits: { '0.99': '%s' }
                              blockedRatio: 1
                            - errorRatio: 0
                              invalidRatio: 0
                              blockedRatio: 0
                """.formatted(name, rate, previous.isEmpty() ? "isWarmup: true" : "startAfterStrict: '" + previous + "'", limit);
    }

    @Test
    void nativeResponseErrorsAndGeneratorLimitsHaveDifferentOutcomes() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().equals("/slow")) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/invalid") ? 500 : 200, 2);
                exchange.getResponseBody().write(new byte[]{'o', 'k'});
                exchange.close();
            });
            server.start();
            try {
                for (String scenario : List.of("response-errors", "session-limit", "session-and-sla", "session-blocking-and-sla", "connection-blocking", "connection-blocking-and-sla", "first-failure")) {
                    boolean combined = scenario.contains("and-sla");
                    // The blocking-only scenario queues several seconds of work; keep its latency SLA out of the way.
                    String failing = phase("main/1", 110, "main/0", combined ? "1ns" : scenario.equals("connection-blocking") ? "30s" : "1s");
                    if (scenario.equals("response-errors")) {
                        failing = failing.replace("GET: /", "GET: /invalid\n            handler:\n              autoRangeCheck: true");
                    } else if (!scenario.equals("first-failure")) {
                        failing = failing.replace("GET: /", "GET: /slow");
                        if (scenario.startsWith("session")) failing = failing.replace("maxSessions: 256", "maxSessions: " + (scenario.contains("blocking") ? 16 : 1));
                    }
                    String yaml = """
                            name: native-errors
                            threads: 1
                            failurePolicy: CANCEL
                            http:
                              host: http://127.0.0.1:%d
                              sharedConnections: %d
                            phases:
                            """.formatted(server.getAddress().getPort(), scenario.contains("blocking") ? 8 : 32)
                            + phase("warmup", 100, "", "1s")
                            + phase("main/0", 100, "warmup", scenario.equals("first-failure") ? "1ns" : "1s")
                            + failing + phase("main/2", 121, "main/1", "1s");
                    var stats = execute(scenario, yaml);
                    var plan = new ThroughputStage("validation", 2000, List.of(
                            new ThroughputStage.Phase("main/0", 100, 2000), new ThroughputStage.Phase("main/1", 110, 2000),
                            new ThroughputStage.Phase("main/2", 121, 2000)));
                    var result = plan.evaluate(stats);
                    String expected = switch (scenario) {
                        case "response-errors", "session-limit", "session-and-sla", "session-blocking-and-sla", "connection-blocking-and-sla" -> "BRACKETED";
                        case "first-failure" -> "INCONCLUSIVE";
                        default -> "GENERATOR_LIMITED";
                    };
                    assertEquals(expected, result.outcome(), () -> scenario + ": " + result + "\n" + JSON.writeValueAsString(stats));
                    assertFalse(result.eligible("main/2"));
                    if (expected.equals("GENERATOR_LIMITED")) assertNull(result.firstFailingRate());
                    else assertFalse(stats.failures().isEmpty(), "Request/latency failures must be evaluated by native SLAs");
                    if (combined) {
                        assertTrue(stats.failures().stream().anyMatch(f -> f.phase().equals("main/1") && f.message().contains("Response time")), result.toString());
                        assertEquals("FAIL", result.phases().get(1).status());
                        assertTrue(result.canValidate());
                        var search = new ThroughputSearch("quick", 100, 1000, "2s", "2s", "2s", 25, 5, 1, 2);
                        assertEquals(138, search.validation(result).phases().getLast().rate());
                    }
                    if (scenario.startsWith("session")) {
                        assertEquals(110, result.firstFailingRate());
                        assertTrue(result.canValidate());
                    }
                }
            } finally {
                server.stop(0);
            }
        }
    }

    private BenchmarkStats execute(String name, String yaml) throws Exception {
        return execute(name, yaml, 60);
    }

    private BenchmarkStats execute(String name, String yaml, int timeoutSeconds) throws Exception {
        String home = System.getenv("HYPERFOIL_HOME");
        assertNotNull(home);
        Path definition = temporary.resolve(name + ".yaml"), output = temporary.resolve(name + ".json"), log = temporary.resolve(name + ".log");
        Files.writeString(definition, yaml);
        var builder = new ProcessBuilder(Path.of(home, "bin/run.sh").toString(), definition.toString(), "--export", output.toString())
                .directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().put("JAVA_OPTS", "-Xmx512m -Dio.hyperfoil.jitter.watchdog.threshold=86400000 -Dio.hyperfoil.cpu.watchdog.idle.threshold=0");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(timeoutSeconds, TimeUnit.SECONDS), "Hyperfoil timed out: " + log);
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(Files.exists(output), Files.readString(log));
            return JSON.readValue(output.toFile(), BenchmarkStats.class);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    @Test
    void stuckSessionsAreTerminatedWithoutEstablishingASutBoundary() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().equals("/hang")) {
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                } else {
                    exchange.sendResponseHeaders(200, 2);
                    exchange.getResponseBody().write(new byte[]{'o', 'k'});
                }
                exchange.close();
            });
            server.start();
            try {
                var search = new ThroughputSearch("quick", 200, 200, "2s", "2s", "2s", 25, 5, 1, 0.1);
                String template = """
                        name: bounded-drain
                        threads: 1
                        failurePolicy: CANCEL
                        agents: {}
                        http:
                          host: http://127.0.0.1:%d
                          sharedConnections: 8
                          requestTimeout: none
                        phases:
                        """.formatted(server.getAddress().getPort())
                        + phase("warmup", 5, "", "1s")
                        + phase("main/0", 200, "warmup", "1s").replace("GET: /", "GET: /hang");
                var plan = search.discovery();
                var result = plan.evaluate(execute("bounded-drain", ThroughputRunner.workload(template, search, plan), 150));
                assertEquals("INVALID", result.outcome(), result.toString());
                assertNull(result.firstFailingRate());
                assertFalse(result.eligible("main/0"));
            } finally {
                release.countDown();
                server.stop(0);
            }
        }
    }

    @Test
    void http2UsesManySessionsOnOnePhysicalConnection() throws Exception {
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var loops = new io.netty.channel.nio.NioEventLoopGroup(1);
        var certificate = new io.netty.pkitesting.CertificateBuilder().setIsCertificateAuthority(true).subject("CN=localhost")
                .addSanDnsName("localhost").buildSelfSigned();
        var ssl = io.netty.handler.ssl.SslContextBuilder.forServer(certificate.getKeyPair().getPrivate(), certificate.getCertificate())
                .applicationProtocolConfig(new io.netty.handler.ssl.ApplicationProtocolConfig(
                        io.netty.handler.ssl.ApplicationProtocolConfig.Protocol.ALPN,
                        io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT, "h2"))
                .build();
        io.netty.channel.Channel listener = null;
        try {
            listener = new io.netty.bootstrap.ServerBootstrap().group(loops)
                    .channel(io.netty.channel.socket.nio.NioServerSocketChannel.class)
                    .childHandler(new io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                        @Override
                        protected void initChannel(io.netty.channel.socket.SocketChannel channel) {
                            channel.pipeline().addLast(ssl.newHandler(channel.alloc()));
                            channel.pipeline().addLast(io.netty.handler.codec.http2.Http2FrameCodecBuilder.forServer().build());
                            channel.pipeline().addLast(new io.netty.handler.codec.http2.Http2MultiplexHandler(
                                    new io.netty.channel.ChannelInitializer<io.netty.channel.Channel>() {
                                        @Override
                                        protected void initChannel(io.netty.channel.Channel stream) {
                                            stream.pipeline().addLast(new io.netty.channel.SimpleChannelInboundHandler<io.netty.handler.codec.http2.Http2Frame>() {
                                                @Override
                                                protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx, io.netty.handler.codec.http2.Http2Frame frame) {
                                                    if (frame instanceof io.netty.handler.codec.http2.Http2HeadersFrame) {
                                                        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                                                        ctx.executor().schedule(() -> {
                                                            ctx.write(new io.netty.handler.codec.http2.DefaultHttp2HeadersFrame(
                                                                    new io.netty.handler.codec.http2.DefaultHttp2Headers().status("200")));
                                                            ctx.writeAndFlush(new io.netty.handler.codec.http2.DefaultHttp2DataFrame(
                                                                    io.netty.buffer.Unpooled.wrappedBuffer(new byte[]{'o', 'k'}), true))
                                                                    .addListener(ignored -> active.decrementAndGet());
                                                        }, 100, TimeUnit.MILLISECONDS);
                                                    }
                                                }
                                            });
                                        }
                                    }));
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
            int port = ((InetSocketAddress) listener.localAddress()).getPort();
            var search = new ThroughputSearch("quick", 200, 200, "2s", "2s", "2s", 25, 5, 1, 2);
            String template = """
                    name: http2-sessions
                    threads: 1
                    failurePolicy: CANCEL
                    agents: {}
                    http:
                      host: https://127.0.0.1:%d
                      allowHttp1x: false
                      allowHttp2: true
                      sharedConnections: 1
                      maxHttp2Streams: 100
                    phases:
                    """.formatted(port) + phase("warmup", 100, "", "1s") + phase("main/0", 200, "warmup", "1s");
            var plan = search.discovery();
            String workload = ThroughputRunner.workload(template, search, plan);
            Path definition = temporary.resolve("http2.yaml");
            Path output = temporary.resolve("http2.json");
            Path log = temporary.resolve("http2.log");
            Files.writeString(definition, workload);
            String home = System.getenv("HYPERFOIL_HOME");
            assertNotNull(home);
            var builder = new ProcessBuilder(Path.of(home, "bin/run.sh").toString(), definition.toString(), "--export", output.toString())
                    .directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
            builder.environment().put("JAVA_OPTS", "-Xmx512m -Dio.hyperfoil.jitter.watchdog.threshold=86400000 -Dio.hyperfoil.cpu.watchdog.idle.threshold=0");
            var process = builder.start();
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), () -> "Hyperfoil timed out: " + log);
                assertEquals(0, process.exitValue(), Files.readString(log));
                assertTrue(Files.exists(output), Files.readString(log));
                var result = plan.evaluate(JSON.readValue(output.toFile(), BenchmarkStats.class));
                assertEquals("LOWER_BOUND", result.outcome(), result.toString());
                assertTrue(peak.get() > 1, "HTTP/2 must serve simultaneous requests on its single connection");
            } finally {
                if (process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            }
        } finally {
            if (listener != null) listener.close().sync();
            loops.shutdownGracefully().sync();
        }
    }
}
