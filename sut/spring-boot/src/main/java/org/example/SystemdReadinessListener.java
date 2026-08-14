package org.example;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "spring.systemd.notify.enabled", havingValue = "true")
final class SystemdReadinessListener implements ApplicationListener<ApplicationReadyEvent> {
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            Process notify = new ProcessBuilder("systemd-notify", "--ready")
                    .inheritIO()
                    .start();
            if (notify.waitFor() != 0) {
                System.exit(1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(1);
        } catch (java.io.IOException e) {
            System.exit(1);
        }
    }
}
