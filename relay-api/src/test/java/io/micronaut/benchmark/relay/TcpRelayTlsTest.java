package io.micronaut.benchmark.relay;

import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TcpRelayTlsTest {
    @Test
    public void test() throws Exception {
        X509Bundle clientCert = new CertificateBuilder()
                .subject("CN=example1.com")
                .setIsCertificateAuthority(true)
                .buildSelfSigned();
        X509Bundle serverCert = new CertificateBuilder()
                .subject("CN=example2.com")
                .setIsCertificateAuthority(true)
                .buildSelfSigned();

        try (TcpRelay client = new TcpRelay().tls(clientCert.getKeyPair().getPrivate(), clientCert.getCertificate(), serverCert.getCertificate());
             TcpRelay server = new TcpRelay().tls(serverCert.getKeyPair().getPrivate(), serverCert.getCertificate(), clientCert.getCertificate())) {
            client.linkTunnel(server.bindTunnel("127.0.0.1", 0));
            testExchange(client);
        }
    }

    private void testExchange(TcpRelay clientRelay) throws Exception {
        try (TcpRelayTest.MockServer server = new TcpRelayTest.MockServer();
             TcpRelay.Binding binding = clientRelay.bindForward(server.address);
             TcpRelayTest.MockClient client = new TcpRelayTest.MockClient(binding.address())) {

            client.writeString("foo");
            assertEquals("foo", server.connection(0).readString());
        }
    }
}
