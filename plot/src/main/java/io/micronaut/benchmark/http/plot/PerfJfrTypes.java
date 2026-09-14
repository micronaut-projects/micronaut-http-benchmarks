package io.micronaut.benchmark.http.plot;

import org.openjdk.jmc.flightrecorder.writer.InlineStringType;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

final class PerfJfrTypes {
    private PerfJfrTypes() {
    }

    static void register(Types types) {
        types.getOrAdd(Types.JDK.ANNOTATION_TIMESTAMP, "java.lang.annotation.Annotation",
                builder -> builder.addField("value", Types.Builtin.STRING));

        Type threadGroup = types.getOrAdd(Types.JDK.THREAD_GROUP, builder -> builder
                .addField("parent", builder.selfType())
                .addField("name", Types.Builtin.STRING));
        types.getOrAdd(Types.JDK.THREAD, builder -> builder
                .addField("osName", Types.Builtin.STRING)
                .addField("osThreadId", Types.Builtin.LONG)
                .addField("javaName", Types.Builtin.STRING)
                .addField("javaThreadId", Types.Builtin.LONG)
                .addField("group", threadGroup)
                .addField("virtual", Types.Builtin.BOOLEAN));

        Type symbol = types.getOrAdd(Types.JDK.SYMBOL, builder -> builder
                .addField("value", InlineStringType.of(types)));
        Type classLoader = types.getOrAdd(Types.JDK.CLASS_LOADER, builder -> builder
                .addField("type", Types.JDK.CLASS)
                .addField("name", symbol));
        Type module = types.getOrAdd(Types.JDK.MODULE, builder -> builder
                .addField("name", symbol)
                .addField("version", symbol)
                .addField("location", symbol)
                .addField("classLoader", classLoader));
        Type packageType = types.getOrAdd(Types.JDK.PACKAGE, builder -> builder
                .addField("name", symbol)
                .addField("module", module)
                .addField("exported", Types.Builtin.BOOLEAN));
        Type classType = types.getOrAdd(Types.JDK.CLASS, builder -> builder
                .addField("classLoader", classLoader)
                .addField("name", symbol)
                .addField("package", packageType)
                .addField("modifiers", Types.Builtin.INT)
                .addField("hidden", Types.Builtin.BOOLEAN));
        Type method = types.getOrAdd(Types.JDK.METHOD, builder -> builder
                .addField("type", classType)
                .addField("name", symbol)
                .addField("descriptor", symbol)
                .addField("modifiers", Types.Builtin.INT)
                .addField("hidden", Types.Builtin.BOOLEAN));
        Type frameType = types.getOrAdd(Types.JDK.FRAME_TYPE,
                builder -> builder.addField("description", Types.Builtin.STRING));
        Type stackFrameWithUnknownBci = types.getOrAdd(Types.JDK.STACK_FRAME.getTypeName(), false, builder -> builder
                .addField("method", method)
                .addField("lineNumber", Types.Builtin.INT)
                .addField("javaFrame", Types.Builtin.BOOLEAN)
                .addField("type", frameType));
        types.getOrAdd(Types.JDK.STACK_TRACE, builder -> builder
                .addField("truncated", Types.Builtin.BOOLEAN)
                .addField(types.fieldBuilder("frames", stackFrameWithUnknownBci).asArray().build()));
    }
}
