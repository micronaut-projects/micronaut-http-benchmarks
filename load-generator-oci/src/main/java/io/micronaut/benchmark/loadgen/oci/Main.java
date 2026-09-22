package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.runtime.Micronaut;

public final class Main {
    public static void main(String[] args) {
        var context = Micronaut.build(args).environments("daemon").start();
        // Own the process lock before accepting work, even if no controller has been requested yet.
        context.getBean(ExperimentQueue.class);
    }
}
