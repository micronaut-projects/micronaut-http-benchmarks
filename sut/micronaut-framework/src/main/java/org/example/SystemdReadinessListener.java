package org.example;

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;

@Singleton
@Requires(property = "micronaut.systemd.notify.enabled", value = "true")
final class SystemdReadinessListener implements ApplicationEventListener<ServerStartupEvent> {
    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        try {
            Process notify = new ProcessBuilder("systemd-notify", "--ready")
                    .inheritIO()
                    .start();
            if (notify.waitFor() != 0) {
                throw new IllegalStateException("systemd-notify failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while notifying systemd", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not notify systemd", e);
        }
    }
}
