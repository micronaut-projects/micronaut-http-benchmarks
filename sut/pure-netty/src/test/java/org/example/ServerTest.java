package org.example;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public class ServerTest {
    private final HttpClient client = HttpClient.newBuilder()
            .sslContext(trustAllSslContext())
            .build();
    private final JsonMapper jsonMapper = new JsonMapper();

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

    @Test
    public void http() throws IOException, InterruptedException {
        try (HttpServer server = new HttpServer()) {
            InetSocketAddress addr = server.bindHttp("localhost", 0);
            byte[] bytes = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + addr.getPort() + "/status")).build(), HttpResponse.BodyHandlers.ofByteArray()).body();
            jsonMapper.readTree(bytes);
        }
    }

    @Test
    public void https1() throws IOException, InterruptedException, CertificateException {
        try (HttpServer server = new HttpServer()) {
            InetSocketAddress addr = server.bindHttps("localhost", 0);
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + addr.getPort() + "/status")).version(HttpClient.Version.HTTP_1_1).build(), HttpResponse.BodyHandlers.ofByteArray());
            Assertions.assertEquals(HttpClient.Version.HTTP_1_1, response.version());
            jsonMapper.readTree(response.body());
        }
    }

    @Test
    public void https2() throws IOException, InterruptedException, CertificateException {
        try (HttpServer server = new HttpServer()) {
            InetSocketAddress addr = server.bindHttps("localhost", 0);
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + addr.getPort() + "/status")).version(HttpClient.Version.HTTP_2).build(), HttpResponse.BodyHandlers.ofByteArray());
            Assertions.assertEquals(HttpClient.Version.HTTP_2, response.version());
            jsonMapper.readTree(response.body());
        }
    }
}
