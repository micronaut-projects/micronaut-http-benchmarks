package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.util.VisibleForTesting;
import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.cmd.SshCommandRunner;
import io.micronaut.benchmark.loadgen.oci.resource.AbstractDecoratedResource;
import io.micronaut.benchmark.loadgen.oci.resource.ResourceContext;
import io.micronaut.benchmark.relay.TcpRelay;
import io.micronaut.scheduling.TaskExecutors;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import io.netty.util.NetUtil;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.core.CoreModuleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;

public final class TcpAgentRelay implements Closeable {
    @VisibleForTesting
    static final String JAVA_PACKAGE = "java-21-openjdk-headless";
    static final int PORT = 8443;
    private static final int LOG_PORT = 8444;

    private static final byte[] AGENT_BYTES;

    private static final Logger LOG = LoggerFactory.getLogger(TcpAgentRelay.class);
    private final OutputListener log;

    private final TcpRelay relay;
    private final SshClient sshClient;

    static {
        try (InputStream stream = TcpAgentRelay.class.getResourceAsStream("/relay-agent-all.jar")) {
            AGENT_BYTES = stream.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private TcpAgentRelay(Builder builder) throws Exception {
        this.log = builder.log;

        CertificateBuilder serverCertBuilder = new CertificateBuilder();
        if (NetUtil.isValidIpV4Address(builder.uri.getHost()) || NetUtil.isValidIpV6Address(builder.uri.getHost())) {
            serverCertBuilder.addSanIpAddress(builder.uri.getHost());
        } else {
            serverCertBuilder.addSanDnsName(builder.uri.getHost());
        }
        X509Bundle serverCert = serverCertBuilder
                .setIsCertificateAuthority(true)
                .subject("CN=" + builder.uri.getHost())
                .buildSelfSigned();
        X509Bundle clientCert = new CertificateBuilder()
                .setIsCertificateAuthority(true)
                .subject("CN=client")
                .buildSelfSigned();

        SshUtil.run(builder.bootstrap, "sudo dnf install -y " + JAVA_PACKAGE, log);
        builder.bootstrap.upload(AGENT_BYTES, "/var/tmp/agent.jar", CommandRunner.DEFAULT_PERMISSIONS);

        StringBuilder agentCommand = new StringBuilder("nohup java -Xmx512M -jar ");
        agentCommand.append("-Dkey.algorithm=").append(serverCert.getKeyPair().getPrivate().getAlgorithm()).append(' ');
        agentCommand.append("-Dkey=").append(Base64.getEncoder().encodeToString(serverCert.getKeyPair().getPrivate().getEncoded())).append(' ');
        agentCommand.append("-Dcert=").append(Base64.getEncoder().encodeToString(serverCert.getCertificate().getEncoded())).append(' ');
        agentCommand.append("-Dremote-cert=").append(Base64.getEncoder().encodeToString(clientCert.getCertificate().getEncoded())).append(' ');
        agentCommand.append("-Dport=").append(PORT).append(' ');
        agentCommand.append("-Dlog-port=").append(LOG_PORT).append(' ');
        OutputListener.Waiter waiter = new OutputListener.Waiter(ByteBuffer.wrap("Tunnel established".getBytes(StandardCharsets.UTF_8)));
        builder.bootstrap.run(agentCommand.append("/var/tmp/agent.jar").toString(), log, waiter);
        waiter.awaitWithNextPattern(null);

        this.relay = new TcpRelay()
                .tls(clientCert.getKeyPair().getPrivate(), clientCert.getCertificate(), serverCert.getCertificate());

        relay.linkTunnel(new InetSocketAddress(builder.uri.getHost(), builder.uri.getPort()));

        sshClient = ClientBuilder.builder()
                .serverKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE)
                .hostConfigEntryResolver(HostConfigEntryResolver.EMPTY)
                .build();
        CoreModuleProperties.SOCKET_KEEPALIVE.set(sshClient, true);
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(sshClient, Duration.ofSeconds(120));
        CoreModuleProperties.AUTH_TIMEOUT.set(sshClient, Duration.ofSeconds(120));
        sshClient.setKeyIdentityProvider(KeyIdentityProvider.wrapKeyPairs(builder.sshKeyPair));
        sshClient.start();

        TcpRelay.Binding logBinding = relay.bindForward(new InetSocketAddress("127.0.0.1", LOG_PORT));
        builder.factory.blocking.execute(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(logBinding.address());
                socket.getInputStream().transferTo(new OutputListener.Stream(List.of(log)));
            } catch (IOException e) {
                LOG.warn("Failed to forward log output", e);
            }
        });
    }

    public CommandRunner openSession(String host) throws IOException {
        URI uri = URI.create("ssh://" + host);
        TcpRelay.Binding binding = relay.bindForward(new InetSocketAddress(uri.getHost(), uri.getPort()));
        SshCommandRunner runner;
        try {
            runner = SshCommandRunner.connect(sshClient, uri.getUserInfo() + "@" + binding.address().getHostString() + ":" + binding.address().getPort());
        } catch (Exception e) {
            binding.close();
            throw e;
        }
        runner.getSession().addCloseFutureListener(_ -> binding.close());
        return runner;
    }

    @Override
    public void close() throws IOException {
        sshClient.close();
        relay.close();
    }

    public final static class TcpRelayResource extends AbstractDecoratedResource {
        private final Builder builder;
        private final Compute.InstanceResource agentInstance;
        private TcpAgentRelay relay;

        private TcpRelayResource(ResourceContext context, Builder builder, Compute.InstanceResource agentInstance) {
            super(context);
            this.builder = builder;
            this.agentInstance = agentInstance;
            dependOn(agentInstance.require());
        }

        @Override
        protected void launchDependencies() {
            AbstractInfrastructure.launch(agentInstance, agentInstance::manage);
        }

        @Override
        protected void setUp() throws Exception {
            try (CommandRunner bootstrap = agentInstance.connectSsh()) {
                SshUtil.openFirewallPorts(bootstrap, builder.log);
                relay = builder
                        .uri(URI.create("https://" + agentInstance.publicIp() + ":" + PORT))
                        .bootstrap(bootstrap)
                        .deploy();
            }
        }

        @Override
        protected void tearDown() throws IOException {
            relay.close();
        }

        public TcpAgentRelay getRelay() {
            return relay;
        }
    }

    public static class Builder {
        private final Factory factory;
        private URI uri;
        private KeyPair sshKeyPair;
        private CommandRunner bootstrap;
        private OutputListener log;

        private Builder(Factory factory) {
            this.factory = factory;
        }

        public Builder uri(URI uri) {
            this.uri = uri;
            return this;
        }

        public Builder sshKeyPair(KeyPair sshKeyPair) {
            this.sshKeyPair = sshKeyPair;
            return this;
        }

        public Builder bootstrap(CommandRunner bootstrap) {
            this.bootstrap = bootstrap;
            return this;
        }

        public Builder log(OutputListener log) {
            this.log = log;
            return this;
        }

        public Builder log(Path log) throws IOException {
            return log(new OutputListener.Write(Files.newOutputStream(log)));
        }

        public TcpAgentRelay deploy() throws Exception {
            return new TcpAgentRelay(this);
        }

        public TcpRelayResource asResource(ResourceContext context, Compute.InstanceResource instanceResource) {
            return new TcpRelayResource(context, this, instanceResource);
        }
    }

    @Singleton
    public record Factory(@Named(TaskExecutors.BLOCKING) ExecutorService blocking) {
        public Builder builder() {
            return new Builder(this);
        }
    }
}
