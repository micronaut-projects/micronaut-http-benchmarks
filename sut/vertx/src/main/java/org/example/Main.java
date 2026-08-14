package org.example;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.transport.Transport;

public class Main {
    public static void main(String[] args) {
        int nThreads = Runtime.getRuntime().availableProcessors();
        Vertx vertx = Vertx.builder()
                .with(new VertxOptions().setEventLoopPoolSize(nThreads))
                .withTransport(Transport.IO_URING)
                .build();
        vertx.deployVerticle(MyVerticle.class, new DeploymentOptions().setInstances(nThreads)).andThen(r -> {
            if (r.failed()) {
                exitWithFailure(vertx, r.cause(), exitCode -> System.exit(exitCode));
            } else {
                try {
                    Process notify = new ProcessBuilder("systemd-notify", "--ready")
                            .inheritIO()
                            .start();
                    if (notify.waitFor() != 0) {
                        throw new IllegalStateException("systemd-notify failed");
                    }
                } catch (Exception e) {
                    exitWithFailure(vertx, e, exitCode -> System.exit(exitCode));
                }
            }
        });
    }

    static void exitWithFailure(Vertx vertx, Throwable cause, java.util.function.IntConsumer exit) {
        vertx.close().onComplete(ignored -> exit.accept(1));
    }
}
