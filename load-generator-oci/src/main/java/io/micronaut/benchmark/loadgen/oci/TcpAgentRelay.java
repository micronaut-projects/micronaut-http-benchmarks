package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.objectstorage.ObjectStorageClient;
import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.cmd.SshCommandRunner;
import io.micronaut.benchmark.loadgen.oci.resource.AbstractDecoratedResource;
import io.micronaut.benchmark.loadgen.oci.resource.ResourceContext;
import io.micronaut.benchmark.relay.TcpRelay;
import io.micronaut.benchmark.relay.TcpRelayMessage;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.scheduling.TaskExecutors;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class TcpAgentRelay implements Closeable {
    static final int PORT = 8443;
    private static final int LOG_PORT = 8444;
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(16);

    private static final Logger LOG = LoggerFactory.getLogger(TcpAgentRelay.class);
    private final OutputListener log;

    private final TcpRelay relay;
    private final SshClient sshClient;

    private TcpAgentRelay(Builder builder) throws Exception {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        this.log = builder.log;

        this.relay = new TcpRelay()
                .tls(builder.clientCert.getKeyPair().getPrivate(), builder.clientCert.getCertificate(), builder.serverCert.getCertificate());

        relay.linkTunnel(new InetSocketAddress(builder.uri.getHost(), builder.uri.getPort()));

        sshClient = ClientBuilder.builder()
                .serverKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE)
                .hostConfigEntryResolver(HostConfigEntryResolver.EMPTY)
                .build();
        remainingDuration(deadline);
        CoreModuleProperties.SOCKET_KEEPALIVE.set(sshClient, true);
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(sshClient, Duration.ofSeconds(120));
        CoreModuleProperties.AUTH_TIMEOUT.set(sshClient, Duration.ofSeconds(120));
        sshClient.setKeyIdentityProvider(KeyIdentityProvider.wrapKeyPairs(builder.sshKeyPair));
        sshClient.start();
        remainingDuration(deadline);

        TcpRelay.Binding logBinding = relay.bindForward(new InetSocketAddress("127.0.0.1", LOG_PORT));
        remainingDuration(deadline);
        @SuppressWarnings("resource")
        Socket socket = new Socket();
        socket.connect(logBinding.address(), remainingMillis(deadline));
        remainingDuration(deadline);
        OutputListener.Waiter tcpLogWaiter = new OutputListener.Waiter(ByteBuffer.wrap(TcpRelayMessage.TCP_LOG_ESTABLISHED.getBytes(StandardCharsets.UTF_8)));
        builder.factory.blocking.execute(() -> {
            try (socket) {
                socket.getInputStream().transferTo(new OutputListener.Stream(List.of(log, tcpLogWaiter)));
            } catch (IOException e) {
                LOG.warn("Failed to forward log output", e);
            }
        });
        // wait for TCP log to start
        tcpLogWaiter.awaitWithNextPattern(null, remainingDuration(deadline));
    }

    private static Duration remainingDuration(long deadline) throws TimeoutException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new TimeoutException("Timed out waiting for relay startup");
        }
        return Duration.ofNanos(remainingNanos);
    }

    private static int remainingMillis(long deadline) throws TimeoutException {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingDuration(deadline).toNanos())));
    }

    public CommandRunner openSession(String host) throws IOException {
        URI uri = URI.create("ssh://" + host);
        TcpRelay.Binding binding = relay.bindForward(new InetSocketAddress(uri.getHost(), uri.getPort()));
        SshCommandRunner runner;
        try {
            runner = Infrastructure.retry(() -> SshCommandRunner.connect(sshClient, uri.getUserInfo() + "@" + binding.address().getHostString() + ":" + binding.address().getPort()));
        } catch (Exception e) {
            binding.close();
            throw e;
        }
        runner.getSession().addCloseFutureListener(ignore -> binding.close());
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
            builder.uri(URI.create("https://" + agentInstance.publicIp() + ":" + PORT));

            if (builder.hasCloudInit) {
                relay = builder.alreadyDeployed();
            } else {
                throw new UnsupportedOperationException("SSH deployment not supported anymore");
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
        private final X509Bundle serverCert = new CertificateBuilder()
                .setIsCertificateAuthority(true)
                .subject("CN=server")
                .addSanDnsName("server")
                .buildSelfSigned();
        private final X509Bundle clientCert = new CertificateBuilder()
                .setIsCertificateAuthority(true)
                .subject("CN=client")
                .buildSelfSigned();

        private URI uri;
        private KeyPair sshKeyPair;
        private OutputListener log;
        private boolean hasCloudInit;

        private Builder(Factory factory) throws Exception {
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

        public Builder log(OutputListener log) {
            this.log = log;
            return this;
        }

        public Builder log(Path log) throws IOException {
            return log(new OutputListener.Write(Files.newOutputStream(log)));
        }

        public Builder prepareCloudInit(Compute.Launch relayInstanceBuilder) throws Exception {
            relayInstanceBuilder.systemdCredential(Path.of("/etc/credstore/relay-agent/key-algorithm"), serverCert.getKeyPair().getPrivate().getAlgorithm().getBytes(StandardCharsets.UTF_8));
            relayInstanceBuilder.systemdCredential(Path.of("/etc/credstore/relay-agent/key"), Base64.getEncoder().encodeToString(serverCert.getKeyPair().getPrivate().getEncoded()).getBytes(StandardCharsets.UTF_8));
            relayInstanceBuilder.systemdCredential(Path.of("/etc/credstore/relay-agent/cert"), Base64.getEncoder().encodeToString(serverCert.getCertificate().getEncoded()).getBytes(StandardCharsets.UTF_8));
            relayInstanceBuilder.systemdCredential(Path.of("/etc/credstore/relay-agent/remote-cert"), Base64.getEncoder().encodeToString(clientCert.getCertificate().getEncoded()).getBytes(StandardCharsets.UTF_8));
            relayInstanceBuilder.nixosConfiguration("relay-server");
            hasCloudInit = true;
            return this;
        }

        private TcpAgentRelay alreadyDeployed() throws Exception {
            return new TcpAgentRelay(this);
        }

        public TcpRelayResource asResource(ResourceContext context, Compute.InstanceResource instanceResource) {
            return new TcpRelayResource(context, this, instanceResource);
        }
    }

    @Singleton
    public record Factory(@Named(TaskExecutors.BLOCKING) ExecutorService blocking, Configuration configuration,
                          ObjectStorageClient objectStorageClient) {
        public Builder builder() throws Exception {
            return new Builder(this);
        }
    }

    @ConfigurationProperties("tcp-agent-relay")
    record Configuration(
            String bucketNamespace,
            String bucketName
    ) {
    }
}
