package org.example;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.internal.VertxInternal;
import io.vertx.core.json.Json;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.util.List;

public class MyVerticle extends AbstractVerticle {
    int httpPort;
    int httpsPort;

    public MyVerticle() {
        this(8080, 8443);
    }

    public MyVerticle(int httpPort, int httpsPort) {
        this.httpPort = httpPort;
        this.httpsPort = httpsPort;
    }

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        Router router = Router.router(vertx);
        router.get("/status").handler(this::status);
        router.post("/search/find").consumes("application/json").handler(BodyHandler.create()).handler(this::find);

        Future<HttpServer> http = vertx.createHttpServer()
                .requestHandler(router)
                .listen(httpPort)
                .onSuccess(event -> httpPort = event.actualPort());
        Future<HttpServer> https = vertx.createHttpServer(new HttpServerOptions()
                        .setSsl(true)
                        .setUseAlpn(true)
                        .addEnabledCipherSuite("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")
                        .addEnabledCipherSuite("TLS_AES_128_GCM_SHA256")
                        .setKeyCertOptions(new PemKeyCertOptions()
                                .setCertPath(tlsPath("server.pem"))
                                .setKeyPath(tlsPath("server-key.pem"))))
                .requestHandler(router)
                .listen(httpsPort)
                .onSuccess(event -> httpsPort = event.actualPort());

        http.flatMap(s -> https).map(v -> (Void) null).andThen(startPromise);
    }

    private void status(RoutingContext routingContext) {
        routingContext.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(Json.encode(new Status(
                        ((VertxInternal) vertx).transport().getClass().getName(),
                        Json.CODEC.getClass().getName()
                )));
    }

    private static String tlsPath(String fileName) {
        return System.getProperty("benchmark.tls.directory", "/etc/benchmark-tls") + "/" + fileName;
    }

    private void find(RoutingContext routingContext) {
        Input input = Json.decodeValue(routingContext.body().buffer(), Input.class);
        Result result = find(input.haystack, input.needle);
        if (result == null) {
            routingContext.response()
                    .setStatusCode(404)
                    .end();
        } else {
            routingContext.response()
                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                    .end(Json.encode(result));
        }
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

    private record Input(List<String> haystack, String needle) {
    }

    private record Result(int listIndex, int stringIndex) {
    }

    private record Status(
            String serverSocketChannelImplementation,
            String jsonCodecImplementation
    ) {
    }
}
