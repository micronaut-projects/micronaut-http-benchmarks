package io.micronaut.benchmark.loadgen.oci;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.Locale;

public final class UptimeConverter extends ClassicConverter {
    @Override
    public String convert(ILoggingEvent event) {
        long uptimeMillis = event.getTimeStamp() - event.getLoggerContextVO().getBirthTime();
        long hours = uptimeMillis / 3_600_000L;
        long minutes = (uptimeMillis % 3_600_000L) / 60_000L;
        long seconds = (uptimeMillis % 60_000L) / 1_000L;
        long milliseconds = uptimeMillis % 1_000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds);
    }
}
