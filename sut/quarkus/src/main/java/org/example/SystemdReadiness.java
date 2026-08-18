package org.example;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.impl.VertxInternal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.util.Locale;

@ApplicationScoped
public class SystemdReadiness {
    private final boolean enabled;
    private final Vertx vertx;

    public SystemdReadiness(@ConfigProperty(name = "benchmark.systemd-readiness.enabled", defaultValue = "false") boolean enabled, Vertx vertx) {
        this.enabled = enabled;
        this.vertx = vertx;
    }

    void onStart(@Observes StartupEvent event) {
        String transportClass = ((VertxInternal) vertx).transport().getClass().getName();
        String normalizedTransportClass = transportClass.toLowerCase(Locale.ROOT);
        if (!normalizedTransportClass.contains("io_uring") && !normalizedTransportClass.contains("iouring")) {
            //throw new IllegalStateException("Vert.x did not select io_uring transport: " + transportClass);
        }
        if (!enabled) {
            return;
        }
        try {
            Process notify = new ProcessBuilder("systemd-notify", "--ready")
                    .inheritIO()
                    .start();
            if (notify.waitFor() != 0) {
                throw new IllegalStateException("systemd-notify failed");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while notifying systemd", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not notify systemd", exception);
        }
    }
}
