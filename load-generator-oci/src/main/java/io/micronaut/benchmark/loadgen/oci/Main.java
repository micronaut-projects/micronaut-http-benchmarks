package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;

public class Main {
    public static void main(String[] args) throws Exception {
        try (ApplicationContext ctx = Micronaut.run(args)) {
            ctx.getBean(SuiteRunner.class).run();
        }
    }
}
