package io.micronaut.benchmark.http.plot;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MainTest {
    @Test
    void loomSupportMapsNixThreadingMetadata() {
        assertEquals("off", Main.loomSupport(null));
        assertEquals("off", Main.loomSupport(Map.of()));
        assertEquals("off", Main.loomSupport(Map.of("threading", "default")));
        assertEquals("on", Main.loomSupport(Map.of("threading", "virtual")));
        assertEquals("carried", Main.loomSupport(Map.of("threading", "loom-carrier")));
        assertThrows(IllegalArgumentException.class,
                () -> Main.loomSupport(Map.of("threading", "unknown")));
    }
}
