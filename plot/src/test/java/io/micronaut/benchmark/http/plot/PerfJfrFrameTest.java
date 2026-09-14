package io.micronaut.benchmark.http.plot;

import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import one.jfr.JfrReader;
import one.jfr.MethodRef;
import one.jfr.StackTrace;
import one.jfr.event.ExecutionSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PerfJfrFrameTest {
    private static final String JAVA_FRAMES = """
             7f io.micronaut.benchmark.Controller::hello [AOT] (benchmark-aot)
             80 JavaMainWrapper::invoke_main [AOT] (benchmark-aot)
             81 Ljava/lang/String;::charAt [JIT] (jitted-456-1.so)
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void decodesOnlyTaggedJavaOwnersAndPreservesNativeAndKernelIdentity() throws Exception {
        Path recording = writeRecording(JAVA_FRAMES + """
                 82 foo;worker+0x12 (libfoo.so)
                 83 std::vector<int>::push_back (libstdc++.so)
                 84 [unknown] ([unknown])
                 85 schedule (app)
                 86 schedule ([kernel.kallsyms])
                 87 io.micronaut.benchmark.Controller::hello [AOT] ([kernel.kallsyms])
                 88 Ljava/lang/String;::charAt [JIT] ([kernel.kallsyms])
                 89 io.micronaut.benchmark.Controller::hello [AOT] (other-app)
                 90 OtherController::hello [AOT] (benchmark-aot)
                 91 Outer::Inner::run+0x12 [AOT] (benchmark-aot)
                 92 Owner::<init> [AOT] (benchmark-aot)
                 93 Owner::method(int) [JIT] (benchmark-aot)
                 94 schedule (/tmp/[kernel.kallsyms])
                 95 schedule ([kernel.kallsyms].backup)
                """);

        assertFrames(recording, List.of(
                javaFrame("io/micronaut/benchmark/Controller", "hello"),
                javaFrame("JavaMainWrapper", "invoke_main"),
                javaFrame("java/lang/String", "charAt"),
                nativeFrame("foo;worker+0x12"),
                nativeFrame("std::vector<int>::push_back"),
                nativeFrame("[unknown]"),
                nativeFrame("schedule"),
                kernelFrame("schedule"),
                kernelFrame("io.micronaut.benchmark.Controller::hello [AOT]"),
                kernelFrame("Ljava/lang/String;::charAt [JIT]"),
                javaFrame("io/micronaut/benchmark/Controller", "hello"),
                javaFrame("OtherController", "hello"),
                javaFrame("Outer::Inner", "run+0x12"),
                javaFrame("Owner", "<init>"),
                javaFrame("Owner", "method(int)"),
                nativeFrame("schedule"),
                nativeFrame("schedule")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "io.micronaut.benchmark.Controller::hello", "JavaMainWrapper::invoke_main", "Ljava/lang/String;::charAt",
            "Owner::method[AOT]", "Owner::method [aot]", "Owner::method [AOT]+0x12", "Owner::method [JIT] extra",
            "Owner::method [AOT] ", "Owner::method [AOT] [clone .isra.0]", "::method [AOT]", "Owner:: [JIT]",
            "method [AOT]", "[unknown]", "_ZN5Owner6methodEv", "foo;worker+0x12", "operator new (unsigned long)"
    })
    void doesNotGuessJavaFromUntaggedOrIncompleteSymbols(String symbol) throws Exception {
        Path recording = writeRecording(" 7f " + symbol + " (benchmark-aot)\n");

        assertFrames(recording, List.of(nativeFrame(symbol)));
    }

    @Test
    void heatmapContainsReadableJavaClassAndMethodNames() throws Exception {
        Path recording = writeRecording(JAVA_FRAMES);
        Path heatmap = temporaryDirectory.resolve("heatmap.html");

        ProfileConverter.convertHeatmap(recording, heatmap);

        String html = Files.readString(heatmap);
        assertTrue(html.contains("\"io.micronaut.benchmark.Controller\""));
        assertTrue(html.contains("\"hello\""));
        assertTrue(html.contains("\"JavaMainWrapper\""));
        assertTrue(html.contains("\"invoke_main\""));
        assertTrue(html.contains("\"java.lang.String\""));
        assertTrue(html.contains("\"charAt\""));
        assertFalse(html.contains("[AOT]"));
        assertFalse(html.contains("[JIT]"));
        assertFalse(html.contains("No samples found"));
    }

    @Test
    void heatmapLocationsAreUnknownWhenFramesComeFromSyntheticPerfJfr() throws Exception {
        Path recording = writeRecording(JAVA_FRAMES + """
                 82 foo;worker+0x12 (libfoo.so)
                 83 schedule ([kernel.kallsyms])
                """);
        Path heatmap = temporaryDirectory.resolve("heatmap.html");

        ProfileConverter.convertHeatmap(recording, heatmap);

        Matcher encodedMethods = Pattern.compile("<pre id=\"methods\">SA(.*?)AE</pre>", Pattern.DOTALL)
                .matcher(Files.readString(heatmap));
        assertTrue(encodedMethods.find());
        IntBuffer data = IntBuffer.wrap(encodedMethods.group(1).chars().map(value -> switch (value) {
            case 127 -> 0;
            case 126 -> '\r';
            case 125 -> '&';
            case 124 -> '<';
            case 123 -> '>';
            default -> value;
        }).toArray());
        int count = readHeatmapVarInt(data);
        assertEquals(6, count);
        List<Integer> bytecodeIndices = new ArrayList<>();
        List<Integer> lineNumbers = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            readHeatmapVarInt(data);
            readHeatmapVarInt(data);
            bytecodeIndices.add(readHeatmapInt18(data));
            lineNumbers.add(readHeatmapInt18(data));
            data.get();
        }
        assertEquals(Collections.nCopies(count, 0xffff), bytecodeIndices, "Unknown BCI must suppress @1 and bci: 1");
        assertEquals(Collections.nCopies(count, 0xffff), lineNumbers);
        assertFalse(data.hasRemaining());
    }

    private static int readHeatmapVarInt(IntBuffer data) {
        int value = 0;
        int scale = 1;
        int digit;
        while ((digit = data.get()) >= 61) {
            value += (digit - 61) * scale;
            scale *= 61;
        }
        return value + digit * scale;
    }

    private static int readHeatmapInt18(IntBuffer data) {
        return data.get() | (data.get() << 6) | (data.get() << 12);
    }

    @Test
    void jdkRendersJavaFramesUsingPublicMetadata() throws Exception {
        Path recording = writeRecording(JAVA_FRAMES);

        String rendered = RecordingFile.readAllEvents(recording).getFirst().toString();

        assertTrue(rendered.contains("io.micronaut.benchmark.Controller.hello"));
        assertTrue(rendered.contains("JavaMainWrapper.invoke_main"));
        assertTrue(rendered.contains("java.lang.String.charAt"));
    }

    private void assertFrames(Path recording, List<ExpectedFrame> expected) throws Exception {
        List<RecordedFrame> jdkFrames = RecordingFile.readAllEvents(recording).getFirst().getStackTrace().getFrames();
        assertEquals(expected.size(), jdkFrames.size());
        for (int index = 0; index < expected.size(); index++) {
            ExpectedFrame expectedFrame = expected.get(index);
            RecordedFrame frame = jdkFrames.get(index);
            if (expectedFrame.javaFrame()) {
                assertEquals(expectedFrame.className(), frame.getMethod().getType().getValue("name"));
                assertEquals(expectedFrame.className().replace('/', '.'), frame.getMethod().getType().getName());
                assertEquals("()V", frame.getMethod().getDescriptor());
            } else {
                assertNull(frame.getMethod().getType().getValue("name"));
                assertNull(frame.getMethod().getDescriptor());
            }
            assertEquals(expectedFrame.methodName(), frame.getMethod().getName());
            assertEquals(expectedFrame.type(), frame.getType());
            assertEquals(expectedFrame.javaFrame(), frame.isJavaFrame());
            assertEquals(expectedFrame.javaFrame(), frame.getBoolean("javaFrame"));
            assertEquals(-1, frame.getLineNumber());
            assertEquals(-1, frame.getBytecodeIndex());
        }

        try (JfrReader reader = new JfrReader(recording.toString())) {
            ExecutionSample sample = reader.readAllEvents(ExecutionSample.class).getFirst();
            StackTrace stack = reader.stackTraces.get(sample.stackTraceId);
            assertEquals(expected.size(), stack.methods.length);
            for (int index = 0; index < expected.size(); index++) {
                ExpectedFrame expectedFrame = expected.get(index);
                MethodRef method = reader.methods.get(stack.methods[index]);
                byte[] className = reader.symbols.get(reader.classes.get(method.cls).name);
                assertEquals(expectedFrame.className(), className == null ? "" : new String(className, StandardCharsets.UTF_8));
                assertEquals(expectedFrame.methodName(), new String(reader.symbols.get(method.name), StandardCharsets.UTF_8));
                if (expectedFrame.javaFrame()) {
                    assertEquals("()V", new String(reader.symbols.get(method.sig), StandardCharsets.UTF_8));
                } else {
                    assertNull(reader.symbols.get(method.sig));
                }
                assertEquals(expectedFrame.typeId(), stack.types[index]);
                assertEquals(expectedFrame.type(), reader.getEnumValue("jdk.types.FrameType", stack.types[index]));
            }
            assertEquals("JIT compiled", reader.getEnumValue("jdk.types.FrameType", 1));
            assertEquals("Inlined", reader.getEnumValue("jdk.types.FrameType", 2));
            assertEquals("Native", reader.getEnumValue("jdk.types.FrameType", 3));
            assertEquals("C++", reader.getEnumValue("jdk.types.FrameType", 4));
            assertEquals("Kernel", reader.getEnumValue("jdk.types.FrameType", 5));
        }
    }

    private Path writeRecording(String frames) throws Exception {
        Path input = temporaryDirectory.resolve("perf-script.txt");
        Files.writeString(input, "worker 1 [000] 1.0: cycles:\n" + frames);
        Path recording = temporaryDirectory.resolve("synthetic.jfr");
        PerfJfrWriter.convert(input, recording);
        return recording;
    }

    private static ExpectedFrame javaFrame(String className, String methodName) {
        return new ExpectedFrame(className, methodName, "JIT compiled", 1, true);
    }

    private static ExpectedFrame nativeFrame(String symbol) {
        return new ExpectedFrame("", symbol, "C++", 4, false);
    }

    private static ExpectedFrame kernelFrame(String symbol) {
        return new ExpectedFrame("", symbol, "Kernel", 5, false);
    }

    private record ExpectedFrame(String className, String methodName, String type, int typeId, boolean javaFrame) {
    }
}
