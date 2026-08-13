package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.model.CaptureConsoleHistoryDetails;
import com.oracle.bmc.core.model.ConsoleHistory;
import com.oracle.bmc.core.requests.CaptureConsoleHistoryRequest;
import com.oracle.bmc.core.requests.DeleteConsoleHistoryRequest;
import com.oracle.bmc.core.requests.GetConsoleHistoryContentRequest;
import com.oracle.bmc.core.requests.GetConsoleHistoryRequest;
import com.oracle.bmc.model.BmcException;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.convert.format.ReadableBytes;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;

public final class ComputeConsoleHistoryCollector {
    private static final Logger LOG = LoggerFactory.getLogger(ComputeConsoleHistoryCollector.class);
    private static final int TERMINAL_DELETE_ATTEMPTS = 3;

    private final Factory factory;
    private final OciLocation location;
    private final String instanceId;
    private final String displayName;
    private final OutputListener listener;
    private final Set<String> pendingDeletes = ConcurrentHashMap.newKeySet();
    private final CompletableFuture<Void> finished = new CompletableFuture<>();
    private volatile boolean finalCaptureRequested;

    private byte[] lastSnapshot = new byte[0];
    private final Thread pollingThread;

    private ComputeConsoleHistoryCollector(Factory factory, OciLocation location, String instanceId,
                                           String displayName, OutputListener listener) {
        this.factory = factory;
        this.location = location;
        this.instanceId = instanceId;
        this.displayName = displayName;
        this.listener = listener;
        this.pollingThread = Thread.ofVirtual().name("oci-console-history-" + location.region() + "-" + instanceId).unstarted(this::poll);
        pollingThread.start();
    }

    public void captureNow() {
        finalCaptureRequested = true;
        LockSupport.unpark(pollingThread);
        finished.join();
    }

    private void poll() {
        try {
            while (!finalCaptureRequested) {
                retryPendingDeletes();
                if (finalCaptureRequested) {
                    break;
                }
                capture();
                if (finalCaptureRequested) {
                    break;
                }
                retryPendingDeletes();
                LockSupport.parkNanos(factory.configuration.pollInterval().toNanos());
            }
        } finally {
            try {
                capture();
            } finally {
                try {
                    retryPendingDeletesAtTermination();
                } finally {
                    try {
                        complete();
                    } finally {
                        finished.complete(null);
                    }
                }
            }
        }
    }

    private void capture() {
        factory.captures.acquireUninterruptibly();
        try {
            captureFromOci();
        } catch (Exception e) {
            LOG.warn("Failed to collect OCI console history for {}", instanceId, e);
        } finally {
            factory.captures.release();
        }
    }

    private void captureFromOci() {
        ComputeClient client = factory.computeClient.forRegion(location);
        String historyId = AbstractInfrastructure.retry(() -> client.captureConsoleHistory(CaptureConsoleHistoryRequest.builder()
                        .captureConsoleHistoryDetails(CaptureConsoleHistoryDetails.builder()
                                .instanceId(instanceId)
                                .displayName("micronaut-benchmark-" + displayName)
                                .build())
                        .opcRetryToken(UUID.randomUUID().toString())
                        .build())
                .getConsoleHistory().getId());
        try {
            long deadline = System.nanoTime() + factory.configuration.captureTimeout().toNanos();
            while (System.nanoTime() < deadline) {
                ConsoleHistory history = client.getConsoleHistory(GetConsoleHistoryRequest.builder().instanceConsoleHistoryId(historyId).build()).getConsoleHistory();
                switch (history.getLifecycleState()) {
                    case Succeeded -> {
                        byte[] current = client.getConsoleHistoryContent(GetConsoleHistoryContentRequest.builder()
                                        .instanceConsoleHistoryId(historyId)
                                        .length(factory.configuration.maxBytes())
                                        .build())
                                .getValue().getBytes(StandardCharsets.UTF_8);
                        appendSnapshot(current);
                        return;
                    }
                    case Failed, UnknownEnumValue -> {
                        LOG.warn("OCI console history capture for {} ended in {}", instanceId, history.getLifecycleState());
                        return;
                    }
                    case Requested, GettingHistory -> LockSupport.parkNanos(factory.configuration.statePollInterval().toNanos());
                }
            }
            LOG.warn("Timed out collecting OCI console history for {}", instanceId);
        } finally {
            deleteHistory(historyId);
        }
    }

    private static ByteBuffer unseenSuffix(byte[] previous, byte[] current) {
        if (current.length == 0) {
            return ByteBuffer.wrap(current);
        }
        int[] prefix = new int[current.length];
        for (int index = 1, matched = 0; index < current.length; index++) {
            while (matched > 0 && current[index] != current[matched]) {
                matched = prefix[matched - 1];
            }
            if (current[index] == current[matched]) {
                matched++;
            }
            prefix[index] = matched;
        }
        int overlap = 0;
        for (byte value : previous) {
            if (overlap == current.length) {
                overlap = prefix[overlap - 1];
            }
            while (overlap > 0 && value != current[overlap]) {
                overlap = prefix[overlap - 1];
            }
            if (value == current[overlap]) {
                overlap++;
            }
        }
        return ByteBuffer.wrap(current, overlap, current.length - overlap);
    }

    private void appendSnapshot(byte[] current) {
        ByteBuffer suffix = unseenSuffix(lastSnapshot, current);
        if (suffix.hasRemaining()) {
            if (lastSnapshot.length != 0 && suffix.remaining() == current.length) {
                deliver(ByteBuffer.wrap("\n--- OCI CONSOLE HISTORY GAP ---\n".getBytes(StandardCharsets.US_ASCII)));
            }
            deliver(suffix);
        }
        lastSnapshot = current;
    }

    private void deliver(ByteBuffer data) {
        try {
            listener.onData(data);
        } catch (Exception e) {
            LOG.warn("Failed to deliver OCI console history for {}", instanceId, e);
        }
    }

    private void complete() {
        try {
            listener.onComplete();
        } catch (Exception e) {
            LOG.warn("Failed to complete OCI console history for {}", instanceId, e);
        }
    }

    private void deleteHistory(String historyId) {
        pendingDeletes.add(historyId);
        try {
            AbstractInfrastructure.retry(() -> {
                try {
                    factory.computeClient.forRegion(location).deleteConsoleHistory(DeleteConsoleHistoryRequest.builder().instanceConsoleHistoryId(historyId).build());
                } catch (BmcException e) {
                    if (e.getStatusCode() != 404) {
                        throw e;
                    }
                }
                return null;
            });
            pendingDeletes.remove(historyId);
        } catch (Exception e) {
            LOG.warn("Failed to delete OCI console history {}", historyId, e);
        }
    }

    private void retryPendingDeletes() {
        for (String historyId : Set.copyOf(pendingDeletes)) {
            deleteHistory(historyId);
        }
    }

    private void retryPendingDeletesAtTermination() {
        for (int attempt = 0; attempt < TERMINAL_DELETE_ATTEMPTS && !pendingDeletes.isEmpty(); attempt++) {
            retryPendingDeletes();
        }
        if (!pendingDeletes.isEmpty()) {
            LOG.warn("Failed to delete OCI console histories for {}: {}", instanceId, pendingDeletes);
        }
    }

    @ConfigurationProperties("console-history")
    public record Configuration(Duration pollInterval, Duration captureTimeout, Duration statePollInterval, @ReadableBytes int maxBytes,
                                int maxConcurrentCaptures) {
        public Configuration {
            if (pollInterval.isNegative() || pollInterval.isZero() || captureTimeout.isNegative() || captureTimeout.isZero()
                    || statePollInterval.isNegative() || statePollInterval.isZero() || maxBytes <= 0 || maxBytes > 1024 * 1024 || maxConcurrentCaptures <= 0) {
                throw new IllegalArgumentException("Console history configuration values must be positive");
            }
        }
    }

    @Singleton
    public static final class Factory {
        private final RegionalClient<ComputeClient> computeClient;
        private final Configuration configuration;
        private final Semaphore captures;

        public Factory(RegionalClient<ComputeClient> computeClient, Configuration configuration) {
            this.computeClient = computeClient;
            this.configuration = configuration;
            this.captures = new Semaphore(configuration.maxConcurrentCaptures());
        }

        public ComputeConsoleHistoryCollector create(OciLocation location, String instanceId, String displayName, OutputListener listener) {
            return new ComputeConsoleHistoryCollector(this, location, instanceId, displayName, listener);
        }
    }
}
