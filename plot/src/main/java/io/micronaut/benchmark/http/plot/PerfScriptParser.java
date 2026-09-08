package io.micronaut.benchmark.http.plot;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PerfScriptParser {
    private static final Pattern HEADER = Pattern.compile(
            "^(.*\\S)\\s+(\\d+)(?:/(\\d+))?\\s+(?:\\[(\\d+)]\\s+)?(\\d+(?:\\.\\d+)?):\\s+" +
                    "(?:(\\d+)\\s+)?(.+):\\s*$");

    private PerfScriptParser() {
    }

    static void parse(BufferedReader input, SampleConsumer consumer) throws IOException {
        Header header = null;
        List<String> frames = new ArrayList<>();
        String line;
        int lineNumber = 0;
        while ((line = input.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                header = emit(header, frames, consumer, lineNumber);
                continue;
            }

            Matcher matcher = HEADER.matcher(line.stripLeading());
            if (matcher.matches()) {
                header = emit(header, frames, consumer, lineNumber);
                header = parseHeader(matcher, lineNumber);
            } else if (Character.isWhitespace(line.charAt(0))) {
                if (header == null) {
                    throw malformed(lineNumber, "orphan stack frame");
                }
                frames.add(normalizeFrame(line));
            } else {
                throw malformed(lineNumber, "malformed sample header");
            }
        }
        emit(header, frames, consumer, lineNumber + 1);
    }

    static Summary summarize(Path input) throws IOException {
        long[] count = {0};
        long[] first = {0};
        long[] previous = {0};
        try (BufferedReader reader = Files.newBufferedReader(input)) {
            parse(reader, sample -> {
                if (count[0] == 0) {
                    first[0] = sample.timestampNanos();
                } else if (sample.timestampNanos() < previous[0]) {
                    throw new IOException("Perf sample " + (count[0] + 1) + " timestamp " +
                            sample.timestampNanos() + " precedes " + previous[0]);
                }
                previous[0] = sample.timestampNanos();
                count[0]++;
            });
        }
        if (count[0] == 0) {
            throw new IOException("Perf script contains no samples");
        }
        try {
            long duration = Math.max(1, Math.addExact(Math.subtractExact(previous[0], first[0]), 1));
            return new Summary(count[0], first[0], previous[0], duration);
        } catch (ArithmeticException e) {
            throw new IOException("Perf timestamp span cannot be represented in synthetic JFR", e);
        }
    }

    private static Header parseHeader(Matcher matcher, int lineNumber) throws IOException {
        long timestamp;
        try {
            timestamp = new BigDecimal(matcher.group(5)).movePointRight(9).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw malformed(lineNumber, "timestamp is not an exact nanosecond value", e);
        }
        try {
            int tid = Integer.parseInt(matcher.group(3) == null ? matcher.group(2) : matcher.group(3));
            int cpu = matcher.group(4) == null ? -1 : Integer.parseInt(matcher.group(4));
            return new Header(matcher.group(1), tid, cpu, timestamp, matcher.group(7));
        } catch (NumberFormatException e) {
            throw malformed(lineNumber, "numeric header value is out of range", e);
        }
    }

    private static Header emit(Header header, List<String> frames, SampleConsumer consumer, int lineNumber)
            throws IOException {
        if (header == null) {
            return null;
        }
        if (frames.isEmpty()) {
            throw malformed(lineNumber, "sample has no stack frames");
        }
        consumer.accept(new Sample(
                header.command(), header.tid(), header.cpu(), header.timestampNanos(), header.event(), List.copyOf(frames)));
        frames.clear();
        return null;
    }

    private static String normalizeFrame(String line) {
        String frame = line.trim();
        int address = frame.indexOf(' ');
        if (address >= 0) {
            frame = frame.substring(address + 1);
        }
        int symbol = frame.indexOf(" (");
        return (symbol < 0 ? frame : frame.substring(0, symbol)).replace(';', ':');
    }

    private static IOException malformed(int lineNumber, String message) {
        return new IOException("perf script line " + lineNumber + ": " + message);
    }

    private static IOException malformed(int lineNumber, String message, Exception cause) {
        return new IOException("perf script line " + lineNumber + ": " + message, cause);
    }

    @FunctionalInterface
    interface SampleConsumer {
        void accept(Sample sample) throws IOException;
    }

    record Sample(String command, int tid, int cpu, long timestampNanos, String event, List<String> frames) {
    }

    record Summary(long count, long firstTimestampNanos, long lastTimestampNanos, long durationNanos) {
    }

    private record Header(String command, int tid, int cpu, long timestampNanos, String event) {
    }
}
