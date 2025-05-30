package org.example;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.async.annotation.SingleResult;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.netty.channel.loom.EventLoopVirtualThreadScheduler;
import io.micronaut.http.netty.channel.loom.PrivateLoomSupport;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import jakarta.inject.Inject;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class LoopController {
    private static final Logger LOG = LoggerFactory.getLogger(LoopController.class);

    private static final java.net.http.HttpClient sharedJdkClient = createClient(Executors.newVirtualThreadPerTaskExecutor());

    private static final AttributeKey<java.net.http.HttpClient> localJdkClient =
            AttributeKey.newInstance("localJdkClient");

    private static java.net.http.HttpClient createClient(Executor executor) {
        return java.net.http.HttpClient.newBuilder()
                .executor(executor)
                .build();
    }

    private static java.net.http.HttpClient getJdkClient() {
        if (Thread.currentThread().isVirtual() &&
                PrivateLoomSupport.isSupported() &&
                PrivateLoomSupport.getScheduler(Thread.currentThread()) instanceof EventLoopVirtualThreadScheduler el) {
            Attribute<java.net.http.HttpClient> attr = el.attributeMap().attr(localJdkClient);
            java.net.http.HttpClient client = attr.get();
            if (client == null) {
                Thread.Builder.OfVirtual builder = Thread.ofVirtual()
                        .name("jdk-client-thread-", 0);
                PrivateLoomSupport.setScheduler(builder, el);
                Executor executor = Executors.newThreadPerTaskExecutor(builder.factory());
                client = createClient(executor);
                if (!attr.compareAndSet(null, client)) {
                    client.close();
                    client = attr.get();
                }
            }
            return client;
        } else {
            return sharedJdkClient;
        }
    }

    private static String handleError(Throwable e) {
        LOG.warn("Error: {}", e.toString());
        return "X";
    }

    @Controller("/loop")
    @Requires(property = "execute-on", value = "blocking")
    @Requires(property = "http-client", value = "jdk")
    public static class BlockingJdk {
        @Value("${loop-remote}")
        String remote;

        @Get
        @ExecuteOn(TaskExecutors.BLOCKING)
        public String get() {
            try {
                return getJdkClient().send(HttpRequest.newBuilder(URI.create(remote + "/hello")).build(), HttpResponse.BodyHandlers.ofString()).body();
            } catch (Exception e) {
                return handleError(e);
            }
        }
    }

    @Controller("/loop")
    @Requires(missingProperty = "execute-on")
    @Requires(property = "http-client", value = "jdk")
    public static class NonBlockingJdk {
        @Value("${loop-remote}")
        String remote;

        @Get
        @SingleResult
        public Publisher<String> get() {
            return Mono.fromFuture(getJdkClient().sendAsync(HttpRequest.newBuilder(URI.create(remote + "/hello")).build(), HttpResponse.BodyHandlers.ofString()).thenApply(HttpResponse::body))
                    .onErrorResume(t -> Mono.just(handleError(t)));
        }
    }

    @Controller("/loop")
    @Requires(property = "execute-on", value = "blocking")
    @Requires(missingProperty = "use-jdk-client")
    public static class BlockingMn {
        @Inject
        @Client("${loop-remote}")
        HttpClient client;

        @Get
        @ExecuteOn(TaskExecutors.BLOCKING)
        public String get() {
            try {
                return client.toBlocking().retrieve("/hello");
            } catch (Exception e) {
                return handleError(e);
            }
        }
    }

    @Controller("/loop")
    @Requires(missingProperty = "execute-on")
    @Requires(missingProperty = "use-jdk-client")
    public static class NonBlockingMn {
        @Inject
        @Client("${loop-remote}")
        HttpClient client;

        @Get
        @SingleResult
        public Publisher<String> get() {
            return Mono.from(client.retrieve("/hello"))
                    .onErrorResume(t -> Mono.just(handleError(t)));
        }
    }
}
