package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Factory;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntry;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.config.keys.loader.pem.RSAPEMResourceKeyPairParser;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Factory class for SSH connections.
 */
@Singleton
public final class SshFactory {
    private static final Logger LOG = LoggerFactory.getLogger(SshFactory.class);

    private final String publicKey;
    private final String privateKey;
    private final SshClient sshClient;
    private final ScheduledExecutorService scheduler;

    SshFactory(SshConfiguration config, @Named(TaskExecutors.SCHEDULED) ExecutorService scheduler) throws Exception {
        this.scheduler = (ScheduledExecutorService) scheduler;
        KeyPair keyPair;
        if (config.privateKeyLocation() == null) {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            keyPair = keyGen.generateKeyPair();
        } else {
            LOG.warn("Using static private key from {}. This private key will be uploaded to the hyperfoil controller, so make sure this key is not valuable!", config.privateKeyLocation);
            keyPair = RSAPEMResourceKeyPairParser.INSTANCE.loadKeyPairs(null, null, null, Files.readAllLines(config.privateKeyLocation())).iterator().next();
        }
        ByteArrayOutputStream publicStream = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePublicKey(keyPair.getPublic(), "micronaut-benchmark", publicStream);
        publicKey = publicStream.toString(StandardCharsets.UTF_8);
        ByteArrayOutputStream privateStream = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "micronaut-benchmark", null, privateStream);
        privateKey = privateStream.toString(StandardCharsets.UTF_8);

        sshClient = ClientBuilder.builder()
                .serverKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE)
                .hostConfigEntryResolver(HostConfigEntryResolver.EMPTY)
                .build();
        CoreModuleProperties.SOCKET_KEEPALIVE.set(sshClient, true);
        sshClient.setKeyIdentityProvider(KeyIdentityProvider.wrapKeyPairs(keyPair));
        sshClient.start();

        LOG.info("Public key: {}", publicKey);
    }

    public String publicKey() {
        return publicKey;
    }

    @Singleton
    @Factory
    public SshClient sshClient() {
        return sshClient;
    }

    /**
     * Connect to the given compute instance.
     *
     * @param instance   The instance to connect to, if it hasn't started yet this method will block until it has
     * @param instanceIp The instance's IP
     * @param relay      Optional SSH relay to use for the connection
     * @return The SSH connection
     */
    public ClientSession connect(
            @Nullable Object instance,
            String instanceIp,
            @Nullable Relay relay
    ) throws Exception {
        int attempts = 0;
        while (true) {
            try {
                ClientSession sess = sshClient.connect(new HostConfigEntry("", instanceIp, 22, "opc", relay == null ? null : relay.username + "@" + relay.relayIp + ":22")).verify().getClientSession();
                sess.auth().verify();
                scheduler.scheduleWithFixedDelay(() -> {
                    if (sess.isClosed()) {
                        // ends the task
                        throw new RuntimeException("End the task");
                    }
                    try {
                        SshUtil.run(sess, "echo keepalive");
                    } catch (Exception e) {
                        LOG.warn("Failed to send keepalive", e);
                    }
                }, 1, 1, TimeUnit.MINUTES);
                return sess;
            } catch (SshException e) {
                // happens before the server has started up
                if (!(e.getCause() instanceof ConnectException ce) || !ce.getMessage().equals("Connection refused")) {
                    // happens sometimes during early start
                    if (!(e.getCause() instanceof IOException ce) || !ce.getMessage().equals("Connection reset")) {
                        if (!e.getMessage().equals("Session is being closed")) {
                            throw e;
                        }
                    }
                }
            }
            TimeUnit.SECONDS.sleep(1);
            if (attempts++ > 500) {
                throw new IOException("Failed to connect to SSH server " + instance + " at " + instanceIp + " via " + relay);
            }
        }
    }

    void deployPrivateKey(ClientSession session) throws IOException {
        ScpClient scpClient = ScpClientCreator.instance().createScpClient(session);
        scpClient.upload(privateKey.getBytes(StandardCharsets.UTF_8), ".ssh/id_rsa", SshUtil.DEFAULT_PERMISSIONS, SshUtil.DEFAULT_TIME);
        scpClient.upload(publicKey.getBytes(StandardCharsets.UTF_8), ".ssh/id_rsa.pub", SshUtil.DEFAULT_PERMISSIONS, SshUtil.DEFAULT_TIME);
    }

    /**
     * @param privateKeyLocation For debugging, the location of the private key to use. Do NOT use a valuable key here,
     *                           as it will be copied to the SSH relay for hyperfoil controller remote control
     */
    @ConfigurationProperties("ssh")
    public record SshConfiguration(@Nullable Path privateKeyLocation) {
    }

    public record Relay(
            String username,
            String relayIp
    ) {
    }
}
