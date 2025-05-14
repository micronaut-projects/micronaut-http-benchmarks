package io.micronaut.benchmark.http.plot;

import one.jfr.JfrReader;
import one.jfr.event.Event;

import java.nio.BufferUnderflowException;

public class RequestEvent extends Event {
    final String cid;
    final long duration;

    public RequestEvent(JfrReader reader) {
        this(new Holder(reader));
    }

    private RequestEvent(Holder holder) {
        super(holder.startTime, holder.eventThread, holder.stackTrace);
        this.cid = holder.cid;
        this.duration = holder.duration;
    }

    private record Holder(
            long startTime,
            long duration,
            int eventThread,
            int stackTrace,
            String cid
    ) {
        Holder(JfrReader reader) {
            this(
                    reader.getVarlong(),
                    reader.getVarlong(),
                    reader.getVarint(),
                    reader.getVarint(),
                    getStringSafe(reader)
            );
        }

        private static String getStringSafe(JfrReader reader) {
            try {
                return reader.getString();
            } catch (BufferUnderflowException bue) {
                return "";
            }
        }
    }
}
