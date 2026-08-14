package org.example;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerTest {
    private final HttpClient client = HttpClient.newBuilder()
            .sslContext(trustAllSslContext())
            .build();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private Vertx vertx;
    private MyVerticle verticle;

    public ServerTest() throws GeneralSecurityException {
    }

    private static SSLContext trustAllSslContext() throws GeneralSecurityException {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new X509TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, SecureRandom.getInstanceStrong());
        return sslContext;
    }

    @BeforeEach
    public void setUp() throws ExecutionException, InterruptedException {
        vertx = Vertx.vertx();
        verticle = new MyVerticle(0, 0);
        vertx.deployVerticle(verticle).toCompletionStage().toCompletableFuture().get();
    }

    @AfterEach
    public void tearDown() throws ExecutionException, InterruptedException {
        vertx.close().toCompletionStage().toCompletableFuture().get();
    }

    @Test
    public void http() throws IOException, InterruptedException {
        byte[] bytes = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + verticle.httpPort + "/status")).build(), HttpResponse.BodyHandlers.ofByteArray()).body();
        String transport = jsonMapper.readTree(bytes).get("serverSocketChannelImplementation").asText();
        Assertions.assertEquals("io.vertx.core.impl.transports.NioTransport", transport);
    }

    @Test
    public void https1() throws IOException, InterruptedException {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + verticle.httpsPort + "/status")).version(HttpClient.Version.HTTP_1_1).build(), HttpResponse.BodyHandlers.ofByteArray());
        Assertions.assertEquals(HttpClient.Version.HTTP_1_1, response.version());
        jsonMapper.readTree(response.body());
    }

    @Test
    public void https2() throws IOException, InterruptedException {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + verticle.httpsPort + "/status")).version(HttpClient.Version.HTTP_2).build(), HttpResponse.BodyHandlers.ofByteArray());
        Assertions.assertEquals(HttpClient.Version.HTTP_2, response.version());
        jsonMapper.readTree(response.body());
    }
}
