package io.micronaut.benchmark.http.plot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

final class PySpyStackMerge {
    private PySpyStackMerge() {
    }

    static void convert(BufferedReader input, Writer output) throws IOException {
        Map<String, Long> stacks = new LinkedHashMap<>();
        String line;
        int lineNumber = 0;
        while ((line = input.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            int countSeparator = line.lastIndexOf(' ');
            if (countSeparator < 0) {
                throw new IOException("Malformed py-spy collapsed line " + lineNumber + ": missing sample count: " + line);
            }
            String stack = line.substring(0, countSeparator);
            String countText = line.substring(countSeparator + 1);
            long count;
            try {
                count = Long.parseLong(countText);
            } catch (NumberFormatException exception) {
                throw new IOException("Malformed py-spy collapsed line " + lineNumber + ": invalid sample count '" + countText + "': " + line, exception);
            }

            String mergedStack = stripProcessFrames(stack);
            if (mergedStack.isEmpty()) {
                continue;
            }
            try {
                stacks.merge(mergedStack, count, Math::addExact);
            } catch (ArithmeticException exception) {
                throw new IOException("Malformed py-spy collapsed line " + lineNumber + ": sample count overflow: " + line, exception);
            }
        }

        for (Map.Entry<String, Long> entry : stacks.entrySet()) {
            output.write(entry.getKey());
            output.write(' ');
            output.write(Long.toString(entry.getValue()));
            output.write('\n');
        }
        output.flush();
    }

    private static String stripProcessFrames(String stack) throws IOException {
        int index = 0;
        int length = stack.length();
        while (index < length) {
            int frameStart = index;
            if (!stack.startsWith("process ", frameStart)) {
                break;
            }
            int pidStart = frameStart + "process ".length();
            int pidEnd = pidStart;
            while (pidEnd < length && Character.isDigit(stack.charAt(pidEnd))) {
                pidEnd++;
            }
            if (pidEnd == pidStart || pidEnd >= length || stack.charAt(pidEnd) != ':') {
                break;
            }
            int commandStart = pidEnd + 1;
            if (commandStart >= length || stack.charAt(commandStart) != '"') {
                break;
            }
            int commandEnd = findQuotedStringEnd(stack, commandStart);
            if (commandEnd < 0) {
                throw new IOException("Malformed py-spy process frame: unterminated quoted command in stack: " + stack);
            }
            index = commandEnd + 1;
            if (index == length) {
                return "";
            }
            if (stack.charAt(index) != ';') {
                break;
            }
            index++;
        }
        return stack.substring(index);
    }

    private static int findQuotedStringEnd(String stack, int quoteStart) {
        for (int index = quoteStart + 1; index < stack.length(); index++) {
            char current = stack.charAt(index);
            if (current == '"' && stack.charAt(index - 1) != '\\') {
                return index;
            }
        }
        return -1;
    }
}
