package io.micronaut.benchmark.http.plot;

import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class PerfJfrWriter {
    private static final long SYNTHETIC_EPOCH_NANOS = 946_684_800_000_000_000L;
    private static final long FIRST_TICK = 1L;

    private final Recording recording;
    private final Type executionSampleType;
    private final Type threadType;
    private final Type stackTraceType;
    private final Type stackFrameType;
    private final Type methodType;
    private final Type symbolType;
    private final TypedValue syntheticClass;
    private final TypedValue frameType;
    private final TypedValue defaultState;
    private final Map<ThreadKey, TypedValue> threads = new HashMap<>();
    private final Map<String, TypedValue> methods = new HashMap<>();
    private final Map<String, TypedValue> frames = new HashMap<>();
    private final Map<String, TypedValue> symbols = new HashMap<>();
    private final long firstPerfTimestamp;
    private long previousPerfTimestamp = Long.MIN_VALUE;

    private PerfJfrWriter(Recording recording, long firstPerfTimestamp) {
        this.recording = recording;
        this.firstPerfTimestamp = firstPerfTimestamp;
        Types types = recording.getTypes();
        PerfJfrTypes.register(types);
        threadType = types.getType(Types.JDK.THREAD);
        stackTraceType = types.getType(Types.JDK.STACK_TRACE);
        stackFrameType = types.getType(Types.JDK.STACK_FRAME);
        methodType = types.getType(Types.JDK.METHOD);
        symbolType = types.getType(Types.JDK.SYMBOL);
        syntheticClass = types.getType(Types.JDK.CLASS).asValue(value -> value.putField("name", symbol("")));
        Type frameTypeDefinition = types.getType(Types.JDK.FRAME_TYPE);
        frameTypeDefinition.asValue(value -> value.putField("description", "JIT compiled"));
        frameTypeDefinition.asValue(value -> value.putField("description", "Inlined"));
        frameType = frameTypeDefinition.asValue(value -> value.putField("description", "Native"));
        Type threadStateType = recording.registerType(
                "jdk.types.ThreadState", type -> type.addField("name", Types.Builtin.STRING));
        defaultState = threadStateType.asValue(value -> value.putField("name", "STATE_DEFAULT"));
        executionSampleType = recording.registerType("jdk.ExecutionSample", "jdk.jfr.Event", type -> type
                .addField("startTime", Types.Builtin.LONG,
                        field -> field.addAnnotation(Types.JDK.ANNOTATION_TIMESTAMP, "TICKS"))
                .addField("sampledThread", Types.JDK.THREAD)
                .addField("stackTrace", Types.JDK.STACK_TRACE)
                .addField("state", threadStateType));
    }

    static void convert(Path input, Path output) throws IOException {
        PerfScriptParser.Summary summary = PerfScriptParser.summarize(input);
        try (Recording recording = Recordings.newRecording(output, settings -> settings
                .withTimestamp(SYNTHETIC_EPOCH_NANOS)
                .withStartTicks(FIRST_TICK)
                .withDuration(summary.durationNanos()))) {
            PerfJfrWriter writer = new PerfJfrWriter(recording, summary.firstTimestampNanos());
            try (var reader = Files.newBufferedReader(input)) {
                PerfScriptParser.parse(reader, writer::writeSample);
            }
        }
    }

    private void writeSample(PerfScriptParser.Sample sample) throws IOException {
        if (previousPerfTimestamp != Long.MIN_VALUE && sample.timestampNanos() < previousPerfTimestamp) {
            throw new IOException("Perf sample timestamp " + sample.timestampNanos() +
                    " precedes " + previousPerfTimestamp);
        }
        previousPerfTimestamp = sample.timestampNanos();
        long tick;
        try {
            tick = Math.addExact(FIRST_TICK, Math.subtractExact(sample.timestampNanos(), firstPerfTimestamp));
        } catch (ArithmeticException e) {
            throw new IOException("Perf timestamp range cannot be represented in synthetic JFR ticks", e);
        }
        TypedValue[] stackFrames = sample.frames().stream().map(this::frame).toArray(TypedValue[]::new);
        TypedValue stackTrace = stackTraceType.asValue(value ->
                value.putField("truncated", false).putField("frames", stackFrames));
        recording.writeEvent(executionSampleType.asValue(value -> value
                .putField("startTime", tick)
                .putField("sampledThread", thread(sample))
                .putField("stackTrace", stackTrace)
                .putField("state", defaultState)));
    }

    private TypedValue thread(PerfScriptParser.Sample sample) {
        ThreadKey key = new ThreadKey(sample.command(), sample.tid());
        return threads.computeIfAbsent(key, ignored -> threadType.asValue(value -> value
                .putField("osName", sample.command())
                .putField("osThreadId", (long) sample.tid())
                .putField("javaName", sample.command())
                .putField("javaThreadId", (long) sample.tid())
                .putField("virtual", false)));
    }

    private TypedValue frame(String symbol) {
        return frames.computeIfAbsent(symbol, ignored -> stackFrameType.asValue(value -> value
                .putField("method", method(symbol))
                .putField("lineNumber", 0)
                .putField("bytecodeIndex", 0)
                .putField("type", frameType)));
    }

    private TypedValue method(String symbol) {
        return methods.computeIfAbsent(symbol, ignored -> methodType.asValue(value -> value
                .putField("type", syntheticClass)
                .putField("name", symbol(symbol))
                .putField("descriptor", symbol(""))));
    }

    private TypedValue symbol(String text) {
        return symbols.computeIfAbsent(text, ignored -> symbolType.asValue(value -> value
                .putField("encoding", (byte) 3)
                .putField("bytes", text.getBytes(StandardCharsets.UTF_8))));
    }

    private record ThreadKey(String command, int tid) {
    }
}
