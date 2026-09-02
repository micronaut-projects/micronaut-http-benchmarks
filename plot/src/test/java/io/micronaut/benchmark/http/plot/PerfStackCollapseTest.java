package io.micronaut.benchmark.http.plot;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PerfStackCollapseTest {
    @Test
    void convertsMultiplePerfStacksToCollapsedFormatWithoutTrailingSeparator() throws Exception {
        String perfScript = """
                benchmark 123 [001] 1.0: cpu-clock:
                 7f foo;worker+0x12 (libfoo.so)
                 7f [unknown] ([unknown])

                benchmark 123 [001] 2.0: cpu-clock:
                 7f baz (libbaz.so)
                 7f qux (libqux.so)""";
        StringWriter collapsed = new StringWriter();

        PerfStackCollapse.convert(new BufferedReader(new StringReader(perfScript)), collapsed);

        assertEquals("[unknown];foo:worker+0x12 1\nqux;baz 1\n", collapsed.toString());
    }
}
