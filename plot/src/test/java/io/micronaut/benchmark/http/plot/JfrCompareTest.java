package io.micronaut.benchmark.http.plot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JfrCompareTest {
    @Test
    public void periodicSampler() {
        int range = 1000000;
        for (JfrCompare.PeriodicSampler sampler : List.of(
                new JfrCompare.PeriodicSampler(1, 3),
                new JfrCompare.PeriodicSampler(2, 3),
                new JfrCompare.PeriodicSampler(3, 3),
                new JfrCompare.PeriodicSampler(5, 100)
        )) {
            long actual = IntStream.range(0, range).filter(i -> sampler.includeNext()).count();
            assertEquals((double) range * sampler.out / sampler.in, actual, 10);
        }
    }
}