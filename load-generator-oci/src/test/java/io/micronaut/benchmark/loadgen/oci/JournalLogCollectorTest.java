package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.cmd.TokenRoutingOutputListener;
import io.micronaut.benchmark.loadgen.oci.cmd.SshCommandRunner;
import io.micronaut.benchmark.api.Nix;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellCommandFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JournalLogCollectorTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path temporary;

    @Test
    void sshCommandReconnectsFromLastCompleteRecord() throws Exception {
        Path a = temporary.resolve("a.json"), b = temporary.resolve("b.json");
        Files.write(a, record("a", "before disconnect"));
        Files.write(b, record("b", "after reconnect"));
        Path first = temporary.resolve("first.sh"), second = temporary.resolve("second.sh");
        Files.writeString(first, "cat " + Nix.shellQuote(a.toString()) + "\nhead -c 20 "
                + Nix.shellQuote(b.toString()) + "\nexit 1\n");
        Files.writeString(second, "cat " + Nix.shellQuote(a.toString()) + " " + Nix.shellQuote(b.toString())
                + "\nexec tail -f /dev/null\n");
        var commands = new CopyOnWriteArrayList<String>();
        var bytes = new ByteArrayOutputStream();
        var received = new CompletableFuture<Void>();
        var output = new OutputListener.Write(bytes, true) {
            @Override
            public synchronized void onData(ByteBuffer data) {
                super.onData(data);
                if (StandardCharsets.UTF_8.decode(data.duplicate()).toString().contains("after reconnect")) {
                    received.complete(null);
                }
            }
        };
        try (SshServer server = SshServer.setUpDefaultServer(); SshClient client = SshClient.setUpDefaultClient()) {
            server.setHost("127.0.0.1");
            server.setPort(0);
            server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(temporary.resolve("host-key")));
            server.setPasswordAuthenticator((user, password, session) -> user.equals("test") && password.equals("test"));
            server.setCommandFactory((channel, command) -> {
                commands.add(command);
                Path script = commands.size() == 1 ? first : second;
                return ProcessShellCommandFactory.INSTANCE.createCommand(channel, "/bin/sh " + Nix.shellQuote(script.toString()));
            });
            server.start();
            client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
            client.addPasswordIdentity("test");
            client.start();
            try (var collector = new JournalLogCollector(
                    () -> SshCommandRunner.connect(client, "test@127.0.0.1:" + server.getPort()), output)) {
                collector.awaitReady(Duration.ofSeconds(10));
                received.get(15, TimeUnit.SECONDS);
            }
        }
        assertEquals(2, commands.size());
        assertTrue(commands.getFirst().contains("--boot=0"));
        assertTrue(commands.getLast().contains("--cursor='a'"));
        assertEquals(1, bytes.toString(StandardCharsets.UTF_8).split("before disconnect", -1).length - 1);
        assertEquals(1, bytes.toString(StandardCharsets.UTF_8).split("after reconnect", -1).length - 1);
    }

    @Test
    void reconnectReplaysPartialRecordWithoutDuplicatingDeliveredEntries() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var target = new OutputListener.Write(bytes, true);
        var cursors = new ArrayList<String>();
        var first = new JournalLogCollector.Records(JSON, target, null, cursors::add);
        byte[] a = record("a", "before disconnect");
        byte[] b = record("b", "replayed \uD83D\uDE80\nsecond line");
        // Cut the connection in the middle of the next JSON record.
        first.write(a);
        first.write(b, 0, b.length - 3);
        first.close();
        assertEquals(List.of("a"), cursors);

        var reconnected = new JournalLogCollector.Records(JSON, target, "a", cursors::add);
        reconnected.write(a);
        for (byte value : b) reconnected.write(value);
        reconnected.write(record("c", "after reconnect"));
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, text.split("before disconnect", -1).length - 1);
        assertEquals(1, text.split("replayed", -1).length - 1);
        assertTrue(text.contains("replayed \uD83D\uDE80\nsecond line"));
        assertTrue(text.endsWith("after reconnect\n"));
        assertEquals(List.of("a", "a", "b", "c"), cursors);
    }

    @Test
    void missingResumeCursorFailsInsteadOfSilentlySkippingLogs() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var cursors = new ArrayList<String>();
        var stream = new JournalLogCollector.Records(JSON, new OutputListener.Write(bytes, true), "vacuumed", cursors::add);
        var error = assertThrows(JournalLogCollector.CaptureException.class, () -> stream.write(record("newer", "lost gap")));
        assertTrue(error.getMessage().contains("resume cursor"));
        assertEquals(0, bytes.size());
        assertTrue(cursors.isEmpty());
    }

    @Test
    void outputFailureDoesNotAdvanceCursorOrAcknowledgeRoutingMarker() throws Exception {
        var failedTarget = new OutputListener.Write(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("Disk full");
            }
        }, true);
        var router = new TokenRoutingOutputListener(new OutputListener.Write(new ByteArrayOutputStream(), true));
        var change = router.switchOn("START", failedTarget);
        var cursors = new ArrayList<String>();
        var stream = new JournalLogCollector.Records(JSON, router, null, cursors::add);
        assertThrows(JournalLogCollector.CaptureException.class, () -> stream.write(record("a", "START")));
        assertTrue(cursors.isEmpty());
        assertFalse(change.observed());
        router.onComplete();
        assertThrows(IllegalStateException.class, () -> change.await(Duration.ofSeconds(1)));
    }

    @Test
    void largeLogBurstRetainsHandoffAndBinaryMessages() throws Exception {
        var boot = new ByteArrayOutputStream();
        var stage = new ByteArrayOutputStream();
        var router = new TokenRoutingOutputListener(new OutputListener.Write(boot, true));
        var change = router.switchOn("START", new OutputListener.Write(stage, true));
        var cursors = new ArrayList<String>();
        var stream = new JournalLogCollector.Records(JSON, router, null, cursors::add);
        String message = "stack frame ".repeat(100);
        for (int i = 0; i < 2000; i++) stream.write(record("before-" + i, message));
        stream.write(record("marker", "START"));
        change.await(Duration.ofSeconds(1));
        stream.write(record("binary", List.of(0, 255, 10)));
        for (int i = 0; i < 2000; i++) stream.write(record("after-" + i, message));
        assertTrue(boot.size() > 1024 * 1024);
        assertTrue(stage.size() > 1024 * 1024);
        assertEquals(4002, cursors.size());
        assertTrue(stage.toString(StandardCharsets.UTF_8).contains("[0,255,10]"));
        assertEquals(2000, boot.toString(StandardCharsets.UTF_8).lines().filter(s -> s.endsWith(message)).count());
        assertEquals(2000, stage.toString(StandardCharsets.UTF_8).lines().filter(s -> s.endsWith(message)).count());
    }

    private static byte[] record(String cursor, Object message) {
        return (JSON.writeValueAsString(Map.of("__CURSOR", cursor, "__REALTIME_TIMESTAMP", "1000000",
                "SYSLOG_IDENTIFIER", "test", "_PID", "123", "MESSAGE", message)) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
