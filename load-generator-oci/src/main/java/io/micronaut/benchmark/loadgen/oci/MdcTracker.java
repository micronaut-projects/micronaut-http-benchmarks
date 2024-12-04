package io.micronaut.benchmark.loadgen.oci;

import org.slf4j.MDC;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Used for logging the benchmark name during parallel benchmarks.
 */
public class MdcTracker {
    private static final String KEY = "benchmarkName";

    private MdcTracker() {}

    public static <T> T withMdc(String benchmarkName, Callable<T> callable) throws Exception {
        MDC.put(KEY, benchmarkName);
        try {
            return callable.call();
        } finally {
            MDC.remove(KEY);
        }
    }

    public static <T> Callable<T> copyMdc(Callable<T> actual) {
        String benchmarkName = MDC.get(KEY);
        Objects.requireNonNull(benchmarkName, "benchmarkName");
        return () -> withMdc(benchmarkName, actual);
    }
}
