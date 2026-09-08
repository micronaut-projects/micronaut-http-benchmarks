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
        PerfScriptParser.parse(input, sample -> writeSample(sample.frames(), output));
    }

    private static void writeSample(List<String> stack, Writer output) throws IOException {
        List<String> rootFirst = new ArrayList<>(stack);
        Collections.reverse(rootFirst);
        output.write(String.join(";", rootFirst));
        output.write(" 1\n");
    }
}
