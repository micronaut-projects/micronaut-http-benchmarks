package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

final class AgnosticRequestHandler {
    static final AgnosticRequestHandler INSTANCE = new AgnosticRequestHandler();

    private final ObjectReader reader = new ObjectMapper().readerFor(Input.class);
    private final ObjectWriter writerResult = new ObjectMapper().writerFor(Result.class);
    private final ObjectWriter writerStatus = new ObjectMapper().writerFor(Status.class);

    ByteBuf find(ChannelHandlerContext ctx, ByteBuf content) throws IOException {
        Input input;
        if (content.hasArray()) {
            input = reader.readValue(content.array(), content.readerIndex() + content.arrayOffset(), content.readableBytes());
        } else {
            input = reader.readValue((InputStream) new ByteBufInputStream(content));
        }

        Result result = find(input.haystack(), input.needle());
        return result == null ? null : serialize(ctx, writerResult, result);
    }

    ByteBuf status(ChannelHandlerContext ctx) throws IOException {
        Status status = new Status(
                ctx.channel().getClass().getName(),
                SslContext.defaultServerProvider()
        );

        return serialize(ctx, writerStatus, status);
    }

    private ByteBuf serialize(ChannelHandlerContext ctx, ObjectWriter writer, Object result) throws IOException {
        ByteBuf buffer = ctx.alloc().buffer();
        writer.writeValue((OutputStream) new ByteBufOutputStream(buffer), result);
        return buffer;
    }

    private static Result find(List<String> haystack, String needle) {
        for (int listIndex = 0; listIndex < haystack.size(); listIndex++) {
            String s = haystack.get(listIndex);
            int stringIndex = s.indexOf(needle);
            if (stringIndex != -1) {
                return new Result(listIndex, stringIndex);
            }
        }
        return null;
    }

    record Status(String channelImplementation,
                  SslProvider sslProvider) {}
}
