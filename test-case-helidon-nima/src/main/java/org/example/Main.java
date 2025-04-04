package org.example;

import io.helidon.common.config.Config;
import io.helidon.common.socket.SocketOptions;
import io.helidon.common.tls.Tls;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http1.Http1Config;
import io.helidon.webserver.http1.Http1ConnectionSelector;
import io.helidon.webserver.http2.Http2Config;
import io.helidon.webserver.http2.Http2ConnectionSelector;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.security.cert.CertificateException;
import java.util.List;

public class Main {
    public static void main(String[] args) throws CertificateException {
        start(8080, 8443);
        System.out.println("Helidon bound");
    }

    static WebServer start(int httpPort, int httpsPort) throws CertificateException {
        SelfSignedCertificate ssc = new SelfSignedCertificate();
        HttpRouting.Builder routing = HttpRouting.builder()
                .post("/search/find", (req, res) -> {
                    Input input = req.content().as(Input.class);
                    Result result = find(input.haystack, input.needle);
                    if (result == null) {
                        res.status(io.helidon.http.Status.NOT_FOUND_404).send();
                    } else {
                        res.send(result);
                    }
                })
                .get("/status", (req, res) -> res.send(new Status()));
        WebServerConfig.Builder builder = WebServer.builder()
                .config(Config.empty())
                .putSocket("http", s -> s.host("0.0.0.0").port(httpPort).routing(routing)
                        .connectionOptions(SocketOptions.builder()
                                .tcpNoDelay(true)
                                .build())
                        .addConnectionSelector(Http1ConnectionSelector.builder().config(Http1Config.builder().build()).build()))
                .putSocket("https", s -> s.tls(Tls.builder()
                        .applicationProtocols(List.of("h2", "http/1.1"))
                        .addEnabledCipherSuite("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")
                        .addEnabledCipherSuite("TLS_AES_128_GCM_SHA256")
                        .privateKey(ssc.key())
                        .privateKeyCertChain(List.of(ssc.cert()))
                        .build())
                        .host("0.0.0.0").port(httpsPort)
                        .connectionOptions(SocketOptions.builder()
                                .tcpNoDelay(true)
                                .build())
                        .addConnectionSelector(Http1ConnectionSelector.builder().config(Http1Config.builder().build()).build())
                        .addConnectionSelector(Http2ConnectionSelector.builder().http2Config(Http2Config.builder().build()).build())
                        .routing(routing));
        return builder.build().start();
    }

    private static Result find(List<String> haystack, String needle) {
        for (int listIndex = 0; listIndex < haystack.size(); listIndex++) {
            String s = haystack.get(listIndex);
            int stringIndex = s.indexOf(needle);
            if (stringIndex != -1) {
                return new Result(listIndex, stringIndex);
            }
        }
        return null;
    }

    public static final class Input {
        private List<String> haystack;
        private String needle;

        public List<String> getHaystack() {
            return haystack;
        }

        public void setHaystack(List<String> haystack) {
            this.haystack = haystack;
        }

        public String getNeedle() {
            return needle;
        }

        public void setNeedle(String needle) {
            this.needle = needle;
        }
    }

    public static final class Result {
        private int listIndex;
        private int stringIndex;

        public Result(int listIndex, int stringIndex) {
            this.listIndex = listIndex;
            this.stringIndex = stringIndex;
        }

        public Result() {
        }

        public int getListIndex() {
            return listIndex;
        }

        public void setListIndex(int listIndex) {
            this.listIndex = listIndex;
        }

        public int getStringIndex() {
            return stringIndex;
        }

        public void setStringIndex(int stringIndex) {
            this.stringIndex = stringIndex;
        }
    }

    public static final class Status {
    }
}