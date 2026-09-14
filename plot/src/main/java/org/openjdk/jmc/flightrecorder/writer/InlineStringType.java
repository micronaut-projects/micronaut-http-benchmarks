package org.openjdk.jmc.flightrecorder.writer;

import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

public final class InlineStringType {
    private InlineStringType() {
    }

    public static Type of(Types types) {
        Type stringType = types.getType(Types.Builtin.STRING);
        // JMC 9.1.2 internal ABI: package-private constructor, classpath loading only (not JPMS).
        return new BuiltinType(stringType.getId(), Types.Builtin.STRING, null, (TypesImpl) types);
    }
}
