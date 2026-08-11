package io.micronaut.benchmark.http.plot;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import one.jfr.JfrReader;
import one.jfr.event.Event;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public class JfrAnalysis {
    public static void main(String[] args) throws IOException {
        try (JfrReader jfr = new JfrReader("/var/tmp/profile.jfr")) {
            jfr.registerEvent("io.micronaut.http.netty.channel.loom.LoomCarrierGroup$ContinuationScheduled", ContinuationEvent.Scheduled.class);
            jfr.registerEvent("io.micronaut.http.netty.channel.loom.LoomCarrierGroup$ContinuationStarted", ContinuationEvent.Started.class);
            jfr.registerEvent("io.micronaut.http.server.netty.handler.Http1RequestEvent", RequestEvent.class);
            jfr.registerEvent("io.micronaut.http.netty.channel.loom.LoomCarrierGroup$LoopTick", LoopTick.class);

            class Loop {
                int lastType;
                long lastTick;

                final Map<Integer, List<Long>> phaseTimes = new HashMap<>();
                int maxActive;

                final Set<Integer> tids = new HashSet<>();

                void handle(Event event, long tx) {
                    tids.add(event.tid);
                    if (event instanceof LoopTick lt) {
                        if (lastType != 0) {
                            phaseTimes.computeIfAbsent(lastType, t -> new ArrayList<>()).add(tx - lastTick);
                        }
                        maxActive = Math.max(maxActive, lt.holder.activeThreads);
                        lastType = lt.holder.type;
                        lastTick = tx;
                    }
                }
            }

            class VThread {
                int tid;
                long start = Long.MAX_VALUE;
                long end = Long.MIN_VALUE;
                long busy = 0;
                List<Range> busyRanges = new ArrayList<>();
                List<Range> scheduledRanges = new ArrayList<>();

                void handle(Event event, long tx) {
                    if (event instanceof ContinuationEvent ce) {
                        if (ce instanceof ContinuationEvent.Started) {
                            busy += dr(jfr, ce.duration);
                            busyRanges.add(new Range(tx, tx + dr(jfr, ce.duration)));
                            scheduledRanges.add(new Range(end, tx));
                        }
                        tid = ce.tid;
                        start = Math.min(start, tx);
                        end = Math.max(end, tx);
                    }
                }

                Range range() {
                    return new Range(start, end);
                }
            }

            Map<Integer, Loop> loops = new LinkedHashMap<>();
            Map<Long, VThread> threads = new LinkedHashMap<>();
            List<RequestEvent> request = new ArrayList<>();
            //int warmup = 60000;
            int warmup = 0;
            List<Event> events = jfr.readAllEvents();
            for (Event event : events) {
                if (warmup > 0) {
                    if (event instanceof RequestEvent) {
                        warmup--;
                    }
                    continue;
                }
                long tx = tx(jfr, event);

                switch (event) {
                    case RequestEvent re -> request.add(re);
                    case LoopTick lt -> loops.computeIfAbsent(lt.holder.loopIndex, k -> new Loop()).handle(event, tx);
                    case ContinuationEvent ce ->
                            threads.computeIfAbsent(ce.hashCode, k -> new VThread()).handle(event, tx);
                    default -> {
                    }
                }
            }
            System.out.println("  n(req): " + request.size());
            System.out.println("P50(req): " + median(request.stream().mapToLong(r -> r.duration)));
            System.out.println("  n(v  ): " + threads.size());
            System.out.println("P50(v  ): " + median(threads.values().stream().mapToLong(vt -> vt.end - vt.start)));
            System.out.println("P50(bsy): " + median(threads.values().stream().mapToLong(vt -> vt.busy)));
            for (int i = 1; i <= 4; i++) {
                int finalI = i;
                System.out.println("P99(ph" + i + "): " + p(0.99, loops.values().stream().flatMapToLong(l -> l.phaseTimes.getOrDefault(finalI, List.of()).stream().mapToLong(v -> v))));
                System.out.println("AVG(ph" + i + "): " + loops.values().stream().flatMapToLong(l -> l.phaseTimes.getOrDefault(finalI, List.of()).stream().mapToLong(v -> v)).average().orElse(-1));
            }

            List<Group> groups = new ArrayList<>();

            //long start = tx(jfr, events.get(events.size() / 2)) + Duration.ofSeconds(10).toNanos();
            long start = tx(jfr, events.getFirst()) + Duration.ofSeconds(25).toNanos();
            Range range = new Range(
                    start,
                    start + Duration.ofMillis(100).toNanos() //Duration.ofMillis(5).toNanos()
            );

            for (Loop loop : loops.values()) {
                System.out.println(loop.maxActive);
            }

            for (int loopIndex : loops.keySet()) {
                Loop loop = loops.get(loopIndex);
                Group group = new Group("Loop " + loopIndex);
                Lane main = new Lane("Tick");
                long last = Long.MIN_VALUE;
                int lastType = 0;
                for (Event event : events) {
                    if (event instanceof LoopTick lt) {
                        if (lt.holder.loopIndex != loopIndex) {
                            continue;
                        }
                        long tx = tx(jfr, event);
                        Range tickRange = new Range(last, tx);
                        if (range.overlaps(tickRange)) {
                            main.data.add(new Block(tickRange.intersect(range), "PH" + lastType));
                        }
                        lastType = lt.holder.type;
                        last = tx;
                    }
                }
                int threadI = 0;
                int reqI = 0;
                int fullyScheduledThreads = 0;
                int fullRunningRequests = 0;
                for (Object o : Stream.concat(
                                threads.values().stream()
                                        .filter(thread -> loop.tids.contains(thread.tid) && thread.range().overlaps(range)),
                                request.stream()
                                        .filter(req -> loop.tids.contains(req.tid) && eventRange(jfr, req, req.duration).overlaps(range))
                        )
                        .sorted(Comparator.comparingLong(o -> o instanceof VThread t ? t.start : tx(jfr, (Event) o)))
                        .toList()) {
                    if (o instanceof VThread thread) {
                        if (thread.scheduledRanges.stream().anyMatch(r -> r.contains(range))) {
                            fullyScheduledThreads++;
                        } else {
                            Lane lane = new Lane("T" + (threadI++));
                            for (Range busy : thread.busyRanges) {
                                if (busy.overlaps(range)) {
                                    lane.data.add(new Block(busy.intersect(range), "busy"));
                                }
                            }
                            for (Range scheduled : thread.scheduledRanges) {
                                if (scheduled.overlaps(range)) {
                                    lane.data.add(new Block(scheduled.intersect(range), "scheduled"));
                                }
                            }
                            if (!lane.data.isEmpty()) {
                                // if it's empty, there was a collision in the thread id, or the thread was sleeping during the analysis time
                                group.data.add(lane);
                            }
                        }
                    } else if (o instanceof RequestEvent req) {
                        Range eventRange = eventRange(jfr, req, req.duration);
                        if (eventRange.contains(range)) {
                            fullRunningRequests++;
                        } else {
                            Lane lane = new Lane("R" + (reqI++));
                            lane.data.add(new Block(eventRange.intersect(range), "request"));
                            group.data.add(lane);
                        }
                    }
                }
                if (fullRunningRequests != 0) {
                    Lane lane = new Lane("R x" + fullRunningRequests);
                    lane.data.add(new Block(range, "request"));
                    group.data.add(lane);
                }
                if (fullyScheduledThreads != 0) {
                    Lane lane = new Lane("T x" + fullyScheduledThreads);
                    lane.data.add(new Block(range, "scheduled"));
                    group.data.add(lane);
                }
                group.data.add(main);
                groups.add(group);
            }

            StringBuilder html = new StringBuilder("""
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="UTF-8">
                 <meta name="viewport" content="width=device-width, initial-scale=1">
                 <title>micronaut-http-benchmarks result</title>
                 <script src='https://cdn.jsdelivr.net/npm/timelines-chart'></script>
                </head>
                <body>
                <script>
                new TimelinesChart(document.body)
                .xTickFormat(n => +n / 1_000_000 + "ms")
                .timeFormat("%Q")
                .zQualitative(true)
                .maxLineHeight(30)
                .maxHeight(1200)
                .data(
""");
            html.append(JsonMapper.builder().build().writeValueAsString(groups));
            html.append(")</script></body></html>");

            Files.writeString(Path.of("output/lanes.html"), html);
            Runtime.getRuntime().exec(new String[]{"firefox", "output/lanes.html"});
        }
    }

    private static long tx(JfrReader jfr, Event event) {
        return (event.time - jfr.chunkStartTicks) * (1_000_000_000L / jfr.ticksPerSec);
    }

    private static long dr(JfrReader jfr, long duration) {
        return duration * (1_000_000_000L / jfr.ticksPerSec);
    }

    private static Range eventRange(JfrReader jfr, Event event, long duration) {
        return new Range(tx(jfr, event), tx(jfr, event) + dr(jfr, duration));
    }

    private static long median(LongStream ls) {
        long[] array = ls.toArray();
        if (array.length == 0) {
            return -1;
        }
        Arrays.sort(array);
        return array[array.length / 2];
    }

    private static long p(double p, LongStream ls) {
        long[] array = ls.toArray();
        if (array.length == 0) {
            return -1;
        }
        Arrays.sort(array);
        return array[(int) (array.length * p)];
    }

    public static class LoopTick extends Event {
        final LoopTickHolder holder;

        public LoopTick(JfrReader reader) {
            this(new LoopTickHolder(reader));
        }

        LoopTick(LoopTickHolder holder) {
            super(holder.startTime, holder.eventThread, holder.stackTrace);
            this.holder = holder;
        }
    }

    record LoopTickHolder(
            long startTime,
            long duration,
            int eventThread,
            int stackTrace,
            int loopIndex,
            int type,
            int activeThreads
    ) {
        LoopTickHolder(JfrReader reader) {
            this(
                    reader.getVarlong(),
                    reader.getVarlong(),
                    reader.getVarint(),
                    reader.getVarint(),
                    reader.getVarint(),
                    reader.getVarint(),
                    reader.getVarint()
            );
        }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Group {
        final String group;
        final List<Lane> data = new ArrayList<>();

        Group(String group) {
            this.group = group;
        }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Lane {
        final String label;
        final List<Block> data = new ArrayList<>();

        Lane(String label) {
            this.label = label;
        }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Block {
        final List<Object> timeRange;
        final Object val;

        Block(Object start, Object end, Object val) {
            this.val = val;
            this.timeRange = List.of(start, end);
        }

        Block(Range range, Object val) {
            this(range.start, range.end, val);
        }
    }

    record Range(long start, long end) {
        boolean overlaps(Range other) {
            return start < other.end && end > other.start;
        }

        boolean contains(Range other) {
            return other.start > start && other.end < end;
        }

        Range intersect(Range other) {
            return new Range(Math.max(start, other.start), Math.min(end, other.end));
        }
    }
}
