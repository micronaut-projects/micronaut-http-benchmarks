package org.example;

import io.helidon.webserver.WebServer;
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

public class NimaServerTest {
    private final HttpClient client = HttpClient.newBuilder()
            .sslContext(trustAllSslContext())
            .build();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private WebServer webServer;

    public NimaServerTest() throws GeneralSecurityException {
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
    public void start() throws CertificateException {
        webServer = Main.start(0, 0);
    }

    @AfterEach
    public void stop() {
        webServer.stop();
    }

    @Test
    public void http() throws IOException, InterruptedException {
        byte[] bytes = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + webServer.port("http") + "/status")).header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofByteArray()).body();
        jsonMapper.readTree(bytes);
    }

    @Test
    public void search() throws IOException, InterruptedException {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + webServer.port("http") + "/search/find"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"haystack\":[\"first\",\"second\"],\"needle\":\"con\"}"))
                .build(), HttpResponse.BodyHandlers.ofByteArray());

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals(1, jsonMapper.readTree(response.body()).get("listIndex").asInt());
        Assertions.assertEquals(2, jsonMapper.readTree(response.body()).get("stringIndex").asInt());
    }

    @Test
    public void https1() throws IOException, InterruptedException {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + webServer.port("https") + "/status")).header("Accept", "application/json").version(HttpClient.Version.HTTP_1_1).build(), HttpResponse.BodyHandlers.ofByteArray());
        Assertions.assertEquals(HttpClient.Version.HTTP_1_1, response.version());
        jsonMapper.readTree(response.body());
    }

    @Test
    public void https2() throws IOException, InterruptedException {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + webServer.port("https") + "/status")).header("Accept", "application/json").version(HttpClient.Version.HTTP_2).build(), HttpResponse.BodyHandlers.ofByteArray());
        Assertions.assertEquals(HttpClient.Version.HTTP_2, response.version());
        jsonMapper.readTree(response.body());
    }
}
