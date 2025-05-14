package io.micronaut.benchmark.http.plot;

import one.jfr.JfrReader;
import one.jfr.event.Event;

public sealed class ContinuationEvent extends Event {
    final long hashCode;
    final long duration;

    public ContinuationEvent(JfrReader reader) {
        this(new Holder(reader));
    }

    private ContinuationEvent(Holder holder) {
        super(holder.startTime, holder.eventThread, holder.stackTrace);
        this.hashCode = holder.hash;
        this.duration = holder.duration;
    }

    private record Holder(
            long startTime,
            long duration,
            int eventThread,
            int stackTrace,
            long hash
    ) {
        Holder(JfrReader reader) {
            this(
                    reader.getVarlong(),
                    reader.getVarlong(),
                    reader.getVarint(),
                    reader.getVarint(),
                    reader.getVarlong()
            );
        }
    }

    public static final class Scheduled extends ContinuationEvent {
        final int mode;
        final int queueDepth;
        final double localBlockPercentage;
        final double targetBlockPercentage;

        public Scheduled(JfrReader reader) {
            super(reader);
            this.mode = reader.getVarint();
            this.queueDepth = reader.getVarint();
            this.localBlockPercentage = reader.getDouble();
            this.targetBlockPercentage = reader.getDouble();
        }
    }

    public static final class Started extends ContinuationEvent {
        final long generation;

        public Started(JfrReader reader) {
            super(reader);
            generation = reader.getVarlong();
        }
    }
}
