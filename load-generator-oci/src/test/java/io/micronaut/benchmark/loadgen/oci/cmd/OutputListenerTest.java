package io.micronaut.benchmark.loadgen.oci.cmd;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputListenerTest {
    @Test
    public void waiterSimple() {
        OutputListener.Waiter waiter = new OutputListener.Waiter(ByteBuffer.wrap("foo".getBytes(StandardCharsets.UTF_8)));
        assertFalse(waiter.found());
        waiter.onData(ByteBuffer.wrap("foo".getBytes(StandardCharsets.UTF_8)));
        assertTrue(waiter.found());
    }

    @Test
    public void waiterIgnoresCsi() {
        OutputListener.Waiter waiter = new OutputListener.Waiter(ByteBuffer.wrap("foo".getBytes(StandardCharsets.UTF_8)));
        assertFalse(waiter.found());
        waiter.onData(ByteBuffer.wrap("fo\033[1;2;3mo".getBytes(StandardCharsets.UTF_8)));
        assertTrue(waiter.found());
    }

    @Test
    public void waiterRecoversFromMismatchAtPatternStart() {
        OutputListener.Waiter waiter = new OutputListener.Waiter(buffer("Moved to TCP log"));
        waiter.onData(buffer("MMoved to TCP log"));
        assertTrue(waiter.found());
    }

    @Test
    public void waiterRecoversFromSelfOverlappingMismatch() {
        OutputListener.Waiter waiter = new OutputListener.Waiter(buffer("aab"));
        waiter.onData(buffer("aaab"));
        assertTrue(waiter.found());
    }

    @Test
    public void logCopiesPartialLineFromReusedBuffer() {
        List<String> lines = new ArrayList<>();
        OutputListener.Log log = new OutputListener.Log(LoggerFactory.getLogger(OutputListenerTest.class), Level.DEBUG) {
            @Override
            protected void log(String msg) {
                lines.add(msg);
            }
        };
        byte[] reused = new byte[8];
        try (OutputListener.Stream stream = new OutputListener.Stream(List.of(log))) {
            write(stream, reused, "a\nb\u00e4");
            write(stream, reused, "c\nd");
        }
        assertEquals(List.of("a", "b\u00e4c", "d"), lines);
    }

    private static void write(OutputListener.Stream stream, byte[] reused, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        java.util.Arrays.fill(reused, (byte) 'x');
        System.arraycopy(bytes, 0, reused, 0, bytes.length);
        stream.write(reused, 0, bytes.length);
        java.util.Arrays.fill(reused, (byte) 'x');
    }

    @Test
    void routesSplitMarkersAndSuppressesTheirBytes() {
        RecordingListener central = new RecordingListener();
        RecordingListener run = new RecordingListener();
        TokenRoutingOutputListener listener = new TokenRoutingOutputListener(central);
        TokenRoutingOutputListener.Switch start = listener.switchOn("[START]", run);

        listener.onData(buffer("before[ST"));
        listener.onData(buffer("ART]during"));
        assertTrue(start.observed());
        start.await(Duration.ofSeconds(1));
        TokenRoutingOutputListener.Switch stop = listener.switchOn("[STOP]", central);
        listener.onData(buffer("[ST"));
        listener.onData(buffer("OP]after"));

        assertEquals("before\n--- OUTPUT SWITCHED TO THIS LOG ---\nafter", central.data());
        assertEquals("\n--- OUTPUT SWITCHED TO THIS LOG ---\nduring", run.data());
        assertTrue(stop.observed());
        stop.await(Duration.ofSeconds(1));
    }

    @Test
    void routesCrLfFollowingMarkerToNewTarget() {
        RecordingListener central = new RecordingListener();
        RecordingListener run = new RecordingListener();
        TokenRoutingOutputListener listener = new TokenRoutingOutputListener(central);
        TokenRoutingOutputListener.Switch outputSwitch = listener.switchOn("marker", run);

        listener.onData(buffer("beforemarker\r\nafter"));

        outputSwitch.await(Duration.ofSeconds(1));
        assertEquals("before", central.data());
        assertEquals("\n--- OUTPUT SWITCHED TO THIS LOG ---\n\r\nafter", run.data());
    }

    @Test
    void rejectsASecondPendingSwitch() {
        RecordingListener central = new RecordingListener();
        TokenRoutingOutputListener listener = new TokenRoutingOutputListener(central);

        listener.switchOn("start", new RecordingListener());

        assertThrows(IllegalStateException.class, () -> listener.switchOn("stop", new RecordingListener()));
    }

    @Test
    void recoversFromPartialTokenPrefixMismatch() {
        RecordingListener central = new RecordingListener();
        RecordingListener run = new RecordingListener();
        TokenRoutingOutputListener listener = new TokenRoutingOutputListener(central);
        TokenRoutingOutputListener.Switch pending = listener.switchOn("start", run);

        listener.onData(buffer("st"));
        listener.onData(buffer("xstartafter"));

        pending.await(Duration.ofSeconds(1));
        assertEquals("stx", central.data());
        assertEquals("\n--- OUTPUT SWITCHED TO THIS LOG ---\nafter", run.data());
    }

    @Test
    void allowsSequentialSwitchesAfterObservation() {
        RecordingListener central = new RecordingListener();
        RecordingListener run = new RecordingListener();
        TokenRoutingOutputListener listener = new TokenRoutingOutputListener(central);
        TokenRoutingOutputListener.Switch start = listener.switchOn("start", run);

        listener.onData(buffer("startpayload"));
        start.await(Duration.ofSeconds(1));
        TokenRoutingOutputListener.Switch stop = listener.switchOn("stop", central);
        listener.onData(buffer("stoptail"));

        stop.await(Duration.ofSeconds(1));
        assertEquals("\n--- OUTPUT SWITCHED TO THIS LOG ---\npayload", run.data());
        assertEquals("\n--- OUTPUT SWITCHED TO THIS LOG ---\ntail", central.data());
    }

    @Test
    void switchTimesOutBeforeItsTokenArrives() {
        TokenRoutingOutputListener listener = new TokenRoutingOutputListener(new RecordingListener());
        TokenRoutingOutputListener.Switch pending = listener.switchOn("start", new RecordingListener());

        assertThrows(IllegalStateException.class, () -> pending.await(Duration.ofMillis(1)));
    }

    @Test
    void cancelRestoresCentralAndTreatsLateTokenAsOrdinary() {
        RecordingListener central = new RecordingListener();
        RecordingListener run = new RecordingListener();
        TokenRoutingOutputListener listener = new TokenRoutingOutputListener(central);
        TokenRoutingOutputListener.Switch start = listener.switchOn("start", run);

        listener.onData(buffer("startrun"));
        start.await(Duration.ofSeconds(1));
        TokenRoutingOutputListener.Switch stop = listener.switchOn("stop", central);
        stop.cancel(central);
        listener.onData(buffer("stopcentral"));

        assertEquals("\n--- OUTPUT SWITCHED TO THIS LOG ---\nrun", run.data());
        assertEquals("stopcentral", central.data());
    }

    @Test
    void cancelFlushesPartialTokenToRestoreTarget() {
        RecordingListener central = new RecordingListener();
        TokenRoutingOutputListener listener = new TokenRoutingOutputListener(central);
        TokenRoutingOutputListener.Switch pending = listener.switchOn("start", new RecordingListener());

        listener.onData(buffer("sta"));
        pending.cancel(central);
        listener.onData(buffer("rtcentral"));

        assertEquals("startcentral", central.data());
    }

    @Test
    void collectorCompletionWakesPendingSwitchAndCompletesCentralOnly() {
        RecordingListener central = new RecordingListener();
        RecordingListener run = new RecordingListener();
        TokenRoutingOutputListener listener = new TokenRoutingOutputListener(central);
        TokenRoutingOutputListener.Switch pending = listener.switchOn("start", run);

        listener.onData(buffer("sta"));
        listener.onComplete();

        assertThrows(IllegalStateException.class, () -> pending.await(Duration.ofSeconds(1)));
        assertEquals("sta", central.data());
        assertEquals(1, central.completions);
        assertEquals(0, run.completions);
    }

    @Test
    void collectorCompletionFlushesPartialTokenToActiveTarget() {
        RecordingListener central = new RecordingListener();
        RecordingListener run = new RecordingListener();
        TokenRoutingOutputListener listener = new TokenRoutingOutputListener(central);
        TokenRoutingOutputListener.Switch start = listener.switchOn("start", run);

        listener.onData(buffer("start"));
        start.await(Duration.ofSeconds(1));
        listener.switchOn("stop", central);
        listener.onData(buffer("sto"));
        listener.onComplete();

        assertEquals("\n--- OUTPUT SWITCHED TO THIS LOG ---\nsto", run.data());
        assertEquals(1, central.completions);
        assertEquals(0, run.completions);
    }

    @Test
    void forwardsLargeOrdinaryInputInOneCall() {
        RecordingListener central = new RecordingListener();
        TokenRoutingOutputListener listener = new TokenRoutingOutputListener(central);
        byte[] data = new byte[1024 * 1024];
        java.util.Arrays.fill(data, (byte) 'x');

        listener.onData(ByteBuffer.wrap(data));

        assertEquals(1, central.calls);
        assertEquals(data.length, central.output.size());
    }

    private static ByteBuffer buffer(String content) {
        return ByteBuffer.wrap(content.getBytes(StandardCharsets.US_ASCII));
    }

    private static final class RecordingListener implements OutputListener {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int completions;
        private int calls;

        @Override
        public void onData(ByteBuffer data) {
            calls++;
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            output.writeBytes(bytes);
        }

        @Override
        public void onComplete() {
            completions++;
        }

        String data() {
            return output.toString(StandardCharsets.US_ASCII);
        }
    }
}
