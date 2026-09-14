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

    @Test
    void preservesNamedAotAndJitFramesAndEscapesSemicolons() throws Exception {
        String perfScript = """
                java 456 [003] 3.0: cycles:
                 7f Ljava/lang/String;::charAt [JIT] (jitted-456-1.so)
                 7f io.micronaut.benchmark.Controller::hello [AOT] (benchmark-aot)
                 7f JavaMainWrapper::invoke_main [AOT] (benchmark-aot)
                """;
        StringWriter collapsed = new StringWriter();

        PerfStackCollapse.convert(new BufferedReader(new StringReader(perfScript)), collapsed);

        assertEquals(
                "JavaMainWrapper::invoke_main [AOT];io.micronaut.benchmark.Controller::hello [AOT];Ljava/lang/String:::charAt [JIT] 1\n",
                collapsed.toString());
    }

    @Test
    void ignoresDsoWhenCollapsingNativeAndKernelFrames() throws Exception {
        String perfScript = """
                GC Thread#0 100/102 [003] 10.0: cycles:
                 7f schedule ([kernel.kallsyms])
                 80 schedule (app)
                 81 std::vector<int>::push_back (libstdc++.so)
                 82 foo;worker+0x12 (libfoo.so)
                 83 [unknown] ([unknown])
                """;
        StringWriter collapsed = new StringWriter();

        PerfStackCollapse.convert(new BufferedReader(new StringReader(perfScript)), collapsed);

        assertEquals("[unknown];foo:worker+0x12;std::vector<int>::push_back;schedule;schedule 1\n", collapsed.toString());
    }
}
