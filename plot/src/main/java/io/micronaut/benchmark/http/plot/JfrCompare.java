package io.micronaut.benchmark.http.plot;

import one.convert.Arguments;
import one.convert.JfrToHeatmap;
import one.jfr.ClassRef;
import one.jfr.Dictionary;
import one.jfr.JfrReader;
import one.jfr.MethodRef;
import one.jfr.StackTrace;
import one.jfr.event.Event;
import one.jfr.event.ExecutionSample;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static one.convert.Frame.TYPE_CPP;
import static one.convert.Frame.TYPE_KERNEL;
import static one.convert.Frame.TYPE_NATIVE;

public class JfrCompare {
    public static void main(String[] args) throws Exception {
        EventList baseline = load("techempower-output/micronaut/profile.jfr");
        String targetFile = "techempower-output/micronaut-loom-on-netty/profile.jfr";
        EventList target = load(targetFile);

        Map<MnStackTrace, Integer> baselineStacks = baseline.getStacks();
        Map<MnStackTrace, Integer> targetStacks = target.getStacks();

        showInteresting("encodeHttpResponseSafe", baselineStacks, targetStacks);

        double ratio = (double) targetStacks.values().stream().mapToLong(l -> l).sum() /
                baselineStacks.values().stream().mapToLong(l -> l).sum();

        Map<MnStackTrace, Integer> highlightStacks = new HashMap<>();
        for (Map.Entry<MnStackTrace, Integer> e : targetStacks.entrySet()) {
            MnStackTrace stackTrace = e.getKey();
            int targetCount = e.getValue();
            int baselineCount = (int) (baselineStacks.getOrDefault(stackTrace, 0) * ratio);
            if (targetCount > baselineCount && baselineCount == 0) { // TODO
                highlightStacks.put(stackTrace, targetCount - baselineCount);
            }
        }

        System.out.println("Highlight " + ((double) highlightStacks.values().stream().mapToLong(l -> l).sum() / targetStacks.values().stream().mapToLong(l -> l).sum()));

        Map<MnStackTrace, PeriodicSampler> samplers = new HashMap<>();

        JfrReader filteredReader = new JfrReader(targetFile) {
            @Override
            public <E extends Event> E readEvent(Class<E> cls) throws IOException {
                while (true) {
                    E e = super.readEvent(cls);
                    if (e instanceof ExecutionSample sample) {
                        MnStackTrace stackTrace = target.toStackTrace(sample.stackTraceId);
                        Integer highlightCount = highlightStacks.get(stackTrace);
                        if (highlightCount == null) {
                            continue;
                        }
                        PeriodicSampler sampler = samplers.computeIfAbsent(stackTrace, s -> new PeriodicSampler(highlightCount.longValue(), targetStacks.get(s).longValue()));
                        int count = 0;
                        for (int i = 0; i < sample.samples; i++) {
                            if (sampler.includeNext()) {
                                count++;
                            }
                        }
                        if (count == 0) {
                            continue;
                        } else if (count != sample.samples) {
                            return (E) new ExecutionSample(sample.time, sample.tid, sample.stackTraceId, sample.threadState, count);
                        }
                    }
                    return e;
                }
            }
        };
        Arguments arguments = new Arguments();
        JfrToHeatmap jfrToFlame = new JfrToHeatmap(filteredReader, arguments);
        jfrToFlame.convert();
        try (OutputStream os = Files.newOutputStream(Path.of("techempower-output/micronaut-loom-on-netty/diff.html"))) {
            jfrToFlame.dump(os);
        }
        Runtime.getRuntime().exec(new String[]{"firefox", "techempower-output/micronaut-loom-on-netty/diff.html"});
    }

    private static void showInteresting(String interest, Map<MnStackTrace, Integer> baselineStacks, Map<MnStackTrace, Integer> targetStacks) {
        for (Map<MnStackTrace, Integer> stacks : List.of(baselineStacks, targetStacks)) {
            System.out.println("Stacks");
            for (MnStackTrace stack : stacks.keySet()) {
                boolean found = false;
                for (StackFrame frame : stack.nonBaseList) {
                    if (frame.method.contains(interest)) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    System.out.println("Found stack: ");
                    for (StackFrame frame : stack.nonBaseList) {
                        System.out.println("at " + frame.method.replace('/', '.') + (frame.method.contains("/") ? "(" + frame.method.substring(frame.method.lastIndexOf('/') + 1, frame.method.lastIndexOf('.')) + ".java:0)" : ""));
                    }
                    break;
                }
            }
        }
    }

    private static EventList load(String path) throws IOException {
        try (JfrReader reader = new JfrReader(path)) {
            return new EventList(reader, reader.readAllEvents());
        }
    }

    private record StackFrame(String method, int location) {
        private static final Set<String> BASE = Set.of(
                "java/lang/Thread.run",
                "io/netty/channel/uring/IoUringIoHandler.handle",
                "io/netty/channel/uring/SubmissionQueue.ioUringEnter",
                "io/netty/channel/DefaultChannelPipeline$HeadContext.channelRead",
                "io/netty/channel/AbstractChannelHandlerContext.fireChannelRead",
                "io/netty/handler/codec/http/HttpObjectEncoder.write",
                "io/micronaut/core/execution/ImperativeExecutionFlowImpl.onComplete",
                "io/micronaut/http/server/netty/RoutingInBoundHandler.writeResponse",
                "io/micronaut/http/server/RouteExecutor.executeRouteAndConvertBody",
                "io/micronaut/http/filter/FilterRunner.filterResponse",
                "io/micronaut/http/server/RouteExecutor.finaliseResponse",
                "io/micronaut/http/server/netty/handler/PipeliningServerHandler.writeSome"
        );
        private static final Set<String> WHISKER = Set.of(
                "asm_common_interrupt",
                "asm_sysvec_apic_timer_interrupt",
                "syscall"
        );

        boolean isBase() {
            return BASE.contains(method);
        }

        boolean isWhisker() {
            return WHISKER.contains(method);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof StackFrame sf && Objects.equals(sf.method, method);
        }

        @Override
        public int hashCode() {
            return method.hashCode();
        }
    }

    private static final class MnStackTrace {
        private final List<StackFrame> frames;
        private final int firstBase;
        private final List<StackFrame> nonBaseList;

        MnStackTrace(List<StackFrame> frames) {
            this.frames = frames;

            int whisker = 0;
            int firstBase = frames.size();
            for (int i = 0; i < frames.size(); i++) {
                if (frames.get(i).isWhisker()) {
                    whisker = i;
                }
                if (frames.get(i).isBase()) {
                    firstBase = i;
                    break;
                }
            }
            this.firstBase = firstBase;
            nonBaseList = frames.subList(whisker, firstBase);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MnStackTrace that && Objects.equals(nonBaseList, that.nonBaseList);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(nonBaseList);
        }

        @Override
        public String toString() {
            return nonBaseList.toString();
        }
    }

    private static class EventList {
        private final JfrReader reader;
        private final List<Event> events;

        private final Dictionary<String> methods = new Dictionary<>();
        private final Dictionary<MnStackTrace> stackTraces = new Dictionary<>();

        EventList(JfrReader reader, List<Event> events) {
            this.reader = reader;
            this.events = events;
        }

        Map<MnStackTrace, Integer> getStacks() {
            return events.stream()
                    .filter(e -> e instanceof ExecutionSample)
                    .collect(Collectors.toMap(
                            e -> toStackTrace(e.stackTraceId),
                            e -> ((ExecutionSample) e).samples,
                            Integer::sum
                    ));
        }

        private MnStackTrace toStackTrace(int stackTrace) {
            MnStackTrace ml = stackTraces.get(stackTrace);
            if (ml == null) {
                StackTrace st = reader.stackTraces.get(stackTrace);
                List<StackFrame> l = new ArrayList<>(st.methods.length);
                for (int i = 0; i < st.methods.length; i++) {
                    l.add(toStackFrame(st.methods[i], st.types[i], st.locations[i]));
                }
                ml = new MnStackTrace(l);
                stackTraces.put(stackTrace, ml);
            }
            return ml;
        }

        private StackFrame toStackFrame(long methodId, byte type, int location) {
            return new StackFrame(methodName(methodId, type), location);
        }

        private static final Pattern LAMBDA_PATTERN = Pattern.compile("(\\$\\$Lambda\\.0x)[0-9a-f]+\\.");

        private String methodName(long id, byte type) {
            String method = methods.get(id);
            if (method == null) {
                method = resolveMethodName(id, type);
                method = LAMBDA_PATTERN.matcher(method).replaceAll("$1.");
                methods.put(id, method);
            }
            return method;
        }

        // from async-profiler

        private String resolveMethodName(long methodId, byte methodType) {
            MethodRef method = reader.methods.get(methodId);
            if (method == null) {
                return "unknown";
            }

            ClassRef cls = reader.classes.get(method.cls);
            byte[] className = reader.symbols.get(cls.name);
            byte[] methodName = reader.symbols.get(method.name);

            if (className == null || className.length == 0 || isNativeFrame(methodType)) {
                return new String(methodName, StandardCharsets.UTF_8);
            } else {
                String classStr = toJavaClassName(className, 0, false);
                if (methodName == null || methodName.length == 0) {
                    return classStr;
                }
                String methodStr = new String(methodName, StandardCharsets.UTF_8);
                return classStr + '.' + methodStr;
            }
        }

        private String toJavaClassName(byte[] symbol, int start, boolean dotted) {
            int end = symbol.length;
            if (start > 0) {
                switch (symbol[start]) {
                    case 'B':
                        return "byte";
                    case 'C':
                        return "char";
                    case 'S':
                        return "short";
                    case 'I':
                        return "int";
                    case 'J':
                        return "long";
                    case 'Z':
                        return "boolean";
                    case 'F':
                        return "float";
                    case 'D':
                        return "double";
                    case 'L':
                        start++;
                        end--;
                }
            }

            String s = new String(symbol, start, end - start, StandardCharsets.UTF_8);
            return dotted ? s.replace('/', '.') : s;
        }

        protected boolean isNativeFrame(byte methodType) {
            // In JDK Flight Recorder, TYPE_NATIVE denotes Java native methods,
            // while in async-profiler, TYPE_NATIVE is for C methods
            return methodType == TYPE_NATIVE && reader.getEnumValue("jdk.types.FrameType", TYPE_KERNEL) != null ||
                    methodType == TYPE_CPP ||
                    methodType == TYPE_KERNEL;
        }
    }

    static final class PeriodicSampler {
        final long out;
        final long in;

        private long pivot;

        PeriodicSampler(long out, long in) {
            assert out <= in;
            this.out = out;
            this.in = in;
        }

        boolean includeNext() {
            pivot += out;
            if (pivot >= in) {
                pivot -= in;
                return true;
            } else {
                return false;
            }
        }
    }
}
