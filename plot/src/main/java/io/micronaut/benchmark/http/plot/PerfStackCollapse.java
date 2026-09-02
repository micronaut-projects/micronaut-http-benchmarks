package io.micronaut.benchmark.http.plot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PerfStackCollapse {
    private PerfStackCollapse() {
    }

    static void convert(BufferedReader input, Writer output) throws IOException {
        List<String> stack = new ArrayList<>();
        String line;
        while ((line = input.readLine()) != null) {
            if (line.isBlank()) {
                writeSample(stack, output);
                stack.clear();
            } else if (line.startsWith("\t") || line.startsWith(" ")) {
                String frame = line.trim();
                int address = frame.indexOf(' ');
                if (address >= 0) {
                    frame = frame.substring(address + 1);
                }
                int symbol = frame.indexOf(" (");
                stack.add((symbol < 0 ? frame : frame.substring(0, symbol)).replace(';', ':'));
            }
        }
        writeSample(stack, output);
    }

    private static void writeSample(List<String> stack, Writer output) throws IOException {
        if (stack.isEmpty()) {
            return;
        }
        Collections.reverse(stack);
        output.write(String.join(";", stack));
        output.write(" 1\n");
    }
}
