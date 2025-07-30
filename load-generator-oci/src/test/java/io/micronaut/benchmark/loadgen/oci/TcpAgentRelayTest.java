package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.cmd.PortForwardHandle;
import io.micronaut.benchmark.loadgen.oci.cmd.ProcessHandle;
import io.micronaut.benchmark.loadgen.oci.cmd.SshCommandRunner;
import io.micronaut.context.BeanContext;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
@Testcontainers(parallel = true)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.CONCURRENT)
class TcpAgentRelayTest {
    private static final int SSH_PORT = 8022;
    private static final KeyPair KEY_PAIR;
    private static final Logger LOG = LoggerFactory.getLogger(TcpAgentRelayTest.class);

    static {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KEY_PAIR = kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("resource")
    @Container
    GenericContainer<?> container = new GenericContainer<>(new ImageFromDockerfile(TcpAgentRelayTest.class.getName().toLowerCase(Locale.ROOT) + "-image", false)
            .withDockerfileFromBuilder(builder -> builder.from("oraclelinux:9")
                    .run("dnf", "install", "socat", "sudo", TcpAgentRelay.JAVA_PACKAGE)
                    .run("useradd", "--create-home", "opc")
                    .run("echo 'opc ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/opc")
                    .run("mkdir", "/home/opc/.ssh")
                    .cmd("ssh-keygen -A && /usr/sbin/sshd -D -p " + SSH_PORT)
                    .expose(SSH_PORT, TcpAgentRelay.PORT)
                    .build())
    )
            .withExposedPorts(SSH_PORT, TcpAgentRelay.PORT)
            .waitingFor(new HostPortWaitStrategy().forPorts(SSH_PORT));

    @Inject
    TcpAgentRelay.Factory agentRelayFactory;

    @TempDir
    Path tmp;

    @AutoClose
    TcpAgentRelay relay;

    @Inject
    BeanContext beanContext;

    @BeforeEach
    void createRelay() throws Exception {
        container.execInContainer("bash", "-c", "echo '" + PublicKeyEntry.toString(KEY_PAIR.getPublic()) + "' > ~opc/.ssh/authorized_keys");

        try (SshClient bootstrapClient = ClientBuilder.builder()
                .serverKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE)
                .hostConfigEntryResolver(HostConfigEntryResolver.EMPTY)
                .build()) {
            bootstrapClient.setKeyIdentityProvider(KeyIdentityProvider.wrapKeyPairs(KEY_PAIR));
            bootstrapClient.start();

            try (CommandRunner bootstrap = SshCommandRunner.connect(bootstrapClient, "opc@" + container.getHost() + ":" + container.getMappedPort(SSH_PORT))) {

                relay = agentRelayFactory.builder()
                        .uri(URI.create("https://" + container.getHost() + ":" + container.getMappedPort(TcpAgentRelay.PORT)))
                        .bootstrap(bootstrap)
                        .sshKeyPair(KEY_PAIR)
                        .log(new OutputListener.Log(LOG, Level.INFO))
                        .deploy();
            }
        }
    }

    private static String output(CommandRunner runner, String command) throws IOException, InterruptedException {
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        try (ProcessHandle handle = runner.run(command, new OutputListener.Write(s))) {
            handle.waitFor();
        }
        return s.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void echo() throws IOException, InterruptedException {
        try (CommandRunner session = relay.openSession("opc@127.0.0.1:" + SSH_PORT)) {
            assertEquals("hi", output(session, "echo -n hi"));
        }
    }

    @Test
    public void uploadSingle() throws IOException, InterruptedException {
        try (CommandRunner session = relay.openSession("opc@127.0.0.1:" + SSH_PORT)) {
            session.upload("foo".getBytes(StandardCharsets.UTF_8), "/tmp/bar", CommandRunner.DEFAULT_PERMISSIONS);

            assertEquals("foo", output(session, "cat /tmp/bar"));
        }
    }

    @Test
    public void uploadRecursive() throws IOException, InterruptedException {
        try (CommandRunner session = relay.openSession("opc@127.0.0.1:" + SSH_PORT)) {
            Files.createDirectory(tmp.resolve("foo"));
            Files.writeString(tmp.resolve("foo").resolve("file1"), "file1-data");
            Files.createDirectory(tmp.resolve("foo").resolve("bar"));
            Files.writeString(tmp.resolve("foo").resolve("bar").resolve("file2"), "file2-data");
            Files.createDirectory(tmp.resolve("foo").resolve("bar").resolve("baz"));

            session.uploadRecursive(tmp.resolve("foo"), "/tmp/foo");

            assertEquals("file1-data", output(session, "cat /tmp/foo/file1"));
            assertEquals("file2-data", output(session, "cat /tmp/foo/bar/file2"));
        }
    }

    @Test
    public void downloadSingle() throws IOException {
        try (CommandRunner session = relay.openSession("opc@127.0.0.1:" + SSH_PORT)) {
            session.runAndCheck("echo -n bar > /tmp/foo");
            assertEquals("bar", new String(session.downloadBytes("/tmp/foo"), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void downloadRecursive() throws IOException {
        try (CommandRunner session = relay.openSession("opc@127.0.0.1:" + SSH_PORT)) {
            session.runAndCheck("mkdir /tmp/foo");
            session.runAndCheck("echo -n file1-data > /tmp/foo/file1");
            session.runAndCheck("mkdir /tmp/foo/bar");
            session.runAndCheck("echo -n file2-data > /tmp/foo/bar/file2");
            session.runAndCheck("mkdir /tmp/foo/bar/baz");

            session.downloadRecursive("/tmp/foo", tmp.resolve("foo"));

            assertEquals("file1-data", Files.readString(tmp.resolve("foo").resolve("file1")));
            assertEquals("file2-data", Files.readString(tmp.resolve("foo").resolve("bar").resolve("file2")));
            assertTrue(Files.isDirectory(tmp.resolve("foo").resolve("bar").resolve("baz")));
        }
    }

    @SuppressWarnings("HttpUrlsUsage")
    @Test
    public void portForward() throws IOException {
        try (CommandRunner session = relay.openSession("opc@127.0.0.1:" + SSH_PORT);
             ProcessHandle _ = session.run("cd /tmp && python3 -m http.server 3456");
             PortForwardHandle forwardHandle = session.portForward(new InetSocketAddress("127.0.0.1", 3456));
             BlockingHttpClient client = beanContext.createBean(HttpClient.class, "http://" + forwardHandle.localAddress().getHostString() + ":" + forwardHandle.localAddress().getPort()).toBlocking()) {

            session.runAndCheck("echo -n bar > /tmp/test");
            assertEquals("bar", client.retrieve("/test"));

            session.runAndCheck("echo -n baz > /tmp/test2");
            assertEquals("baz", client.retrieve("/test2"));
        }
    }
}