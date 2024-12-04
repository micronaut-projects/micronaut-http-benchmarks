package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.core.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Tracker for progress of different benchmarks.
 */
public final class PhaseTracker {
    private static final Logger LOG = LoggerFactory.getLogger(PhaseTracker.class);

    private final ObjectMapper objectMapper;
    private final Path outputNew;
    private final Path outputOld;

    private final Map<String, BenchmarkPhase> phases = new HashMap<>();
    private final List<Record> records = new CopyOnWriteArrayList<>();

    public PhaseTracker(ObjectMapper objectMapper, Path outputDir) {
        this.objectMapper = objectMapper;
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
        return (phase, percent, msg) -> update(name, phase, percent);
    }

    private void update(String name, BenchmarkPhase phase, double phasePercentage) {
        synchronized (phases) {
            phases.put(name, phase);
        }
        records.add(new Record(Instant.now(), name, phase, phasePercentage));
    }

    /**
     * Regularly log and save benchmark progress.
     */
    public void trackLoop() throws IOException {
        int lastSize = 0;
        while (true) {
            int newSize = records.size();
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
                dump();
                Files.move(outputNew, outputOld, StandardCopyOption.REPLACE_EXISTING);
                break;
            }
        }
    }

    private void dump() throws IOException {
        objectMapper.writeValue(outputNew.toFile(), new Dump(Instant.now(), BenchmarkPhase.values(), records));
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
            double phasePercentage
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
