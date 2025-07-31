package io.micronaut.benchmark.relay.agent;

import io.micronaut.benchmark.relay.TcpRelay;
import io.micronaut.benchmark.relay.TcpRelayMessage;
import io.netty.buffer.ByteBufAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

public class Main {
    static {
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS");
    }

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws GeneralSecurityException, IOException {
        Integer logPort = Integer.getInteger("log-port");
        ServerSocket logServer = null;
        if (logPort != null) {
            logServer = new ServerSocket();
            logServer.setReuseAddress(true);
            logServer.bind(new InetSocketAddress("127.0.0.1", logPort));
            LOG.info("Bound log server to {}", logPort);
        }

        KeyFactory kf = KeyFactory.getInstance(System.getProperty("key.algorithm", "RSA"));
        CertificateFactory cf = CertificateFactory.getInstance("X509");
        new TcpRelay()
                .tls(
                        kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(System.getProperty("key")))),
                        (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(System.getProperty("cert")))),
                        (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(System.getProperty("remote-cert"))))
                )
                .logBufferOccupancy(10, TimeUnit.SECONDS)
                .bindTunnel(
                        System.getProperty("host", "0.0.0.0"),
                        Integer.getInteger("port", 8443)
                );

        LOG.info(TcpRelayMessage.TUNNEL_ESTABLISHED);

        if (logServer != null) {
            Socket socket = logServer.accept();

            LOG.info("Moving to TCP log...");

            AsyncOutputStream asyncOutputStream = new AsyncOutputStream(ByteBufAllocator.DEFAULT, socket.getOutputStream(), 65536);
            PrintStream printStream = new PrintStream(asyncOutputStream, true);
            printStream.println("TCP log test message");
            System.setErr(printStream);

            logServer.close();

            LOG.info(TcpRelayMessage.TCP_LOG_ESTABLISHED);
            asyncOutputStream.run();
        }
    }
}
