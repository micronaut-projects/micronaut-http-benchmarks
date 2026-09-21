package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.core.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Tracker for progress of different benchmarks.
 */
public final class PhaseTracker {
    private static final Logger LOG = LoggerFactory.getLogger(PhaseTracker.class);

    private final ObjectMapper objectMapper;
    private final Path outputTemporary;
    private final Path outputNew;
    private final Path outputOld;

    private final Map<String, BenchmarkPhase> phases = new HashMap<>();
    private final Map<String, Record> latestRecords = new HashMap<>();
    private final Map<String, Record> phaseStartedRecords = new HashMap<>();
    private final List<Record> records = new ArrayList<>();

    public PhaseTracker(ObjectMapper objectMapper, Path outputDir) {
        this.objectMapper = objectMapper;
        this.outputTemporary = outputDir.resolve("phases.new.json.tmp");
        this.outputNew = outputDir.resolve("phases.new.json");
        this.outputOld = outputDir.resolve("phases.json");
    }

    /**
     * Create a new {@link PhaseUpdater} for the given benchmark name.
     *
     * @param name benchmark name
     * @return A progress updater for that benchmark
     */
    public PhaseUpdater updater(String name) {
        return (phase, percent, displayProgress) -> update(name, phase, percent, displayProgress);
    }

    private void update(String name, BenchmarkPhase phase, double phasePercentage, @Nullable String displayProgress) {
        Record previous;
        Record phaseStart;
        Record record;
        synchronized (phases) {
            Instant now = Instant.now();
            previous = latestRecords.get(name);
            phaseStart = phaseStartedRecords.get(name);
            record = new Record(
                    now,
                    name,
                    phase,
                    phasePercentage,
                    displayProgress,
                    previous == null ? null : Duration.between(previous.time(), now)
            );
            phases.put(name, phase);
            latestRecords.put(name, record);
            if (previous == null || previous.phase() != phase) {
                phaseStartedRecords.put(name, record);
            }
            records.add(record);
        }
        if (previous != null && previous.phase() != phase) {
            LOG.info("Benchmark {} changed phase from {} to {} after {}", name, previous.phase(), phase,
                    Duration.between(phaseStart.time(), record.time()));
        }
    }

    /**
     * Start background progress reporting. Closing the returned handle waits for the writer to stop and saves
     * the final snapshot, even when the caller is interrupted.
     */
    public AutoCloseable start() {
        Thread thread = Thread.ofVirtual().name("benchmark-progress").start(() -> {
            try {
                trackLoop();
            } catch (IOException e) {
                LOG.error("Error in phase tracker", e);
            }
        });
        return () -> {
            thread.interrupt();
            boolean interrupted = Thread.interrupted();
            try {
                while (thread.isAlive()) {
                    try {
                        thread.join();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                finalizeSnapshot();
                if (interrupted) {
                    throw new InterruptedException("Interrupted while stopping benchmark progress reporting");
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    private void trackLoop() throws IOException {
        int lastSize = 0;
        while (true) {
            int newSize;
            synchronized (phases) {
                newSize = records.size();
            }
            if (newSize != lastSize) {
                lastSize = newSize;
                dump();
            }
            List<BenchmarkPhase> phases;
            synchronized (this.phases) {
                phases = new ArrayList<>(this.phases.values());
            }
            Map<BenchmarkPhase, Long> countByPhase = phases.stream()
                    .collect(Collectors.groupingBy(ph -> ph, () -> new EnumMap<>(BenchmarkPhase.class), Collectors.counting()));
            LOG.info("Benchmark status: {}", countByPhase
                    .entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(" ")));
            try {
                TimeUnit.SECONDS.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void dump() throws IOException {
        Dump snapshot;
        synchronized (phases) {
            snapshot = new Dump(Instant.now(), BenchmarkPhase.values(), List.copyOf(records));
        }
        objectMapper.writeValue(outputTemporary.toFile(), snapshot);
        try {
            Files.move(outputTemporary, outputNew, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(outputTemporary, outputNew, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void finalizeSnapshot() throws IOException {
        dump();
        try {
            Files.move(outputNew, outputOld, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(outputNew, outputOld, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    record Dump(
            Instant end,
            BenchmarkPhase[] phases,
            List<Record> records
    ) {}

    record Record(
            Instant time,
            String name,
            BenchmarkPhase phase,
            double phasePercentage,
            @Nullable String displayProgress,
            @Nullable Duration elapsed
    ) {}

    public interface PhaseUpdater {
        void update(BenchmarkPhase phase, double percent, @Nullable String displayProgress);

        default void update(BenchmarkPhase phase) {
            update(phase, 0, null);
        }
    }

    static abstract class DelegatePhaseUpdater implements PhaseUpdater {
        private final PhaseUpdater delegate;

        DelegatePhaseUpdater(PhaseUpdater delegate) {
            this.delegate = delegate;
        }

        @Override
        public void update(BenchmarkPhase phase, double percent, @Nullable String displayProgress) {
            delegate.update(phase, percent, displayProgress);
        }
    }
}
