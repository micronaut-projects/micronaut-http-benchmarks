package io.micronaut.benchmark.loadgen.oci;

import ch.qos.logback.classic.spi.LoggerContextVO;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UptimeConverterTest {
    @Test
    void formatsElapsedTimeSinceLoggerContextBirth() {
        UptimeConverter converter = new UptimeConverter();
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContextRemoteView(new LoggerContextVO("test", Map.of(), 1L));
        event.setTimeStamp(3_666_008L);

        assertEquals("01:01:06.007", converter.convert(event));
    }

    @Test
    void formatsHoursBeyondTwentyFour() {
        UptimeConverter converter = new UptimeConverter();
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContextRemoteView(new LoggerContextVO("test", Map.of(), 1L));
        event.setTimeStamp(100_000_001L);

        assertEquals("27:46:40.000", converter.convert(event));
    }
}
