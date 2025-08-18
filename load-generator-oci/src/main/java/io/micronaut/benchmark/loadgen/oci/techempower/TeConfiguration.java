package io.micronaut.benchmark.loadgen.oci.techempower;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * @param compartmentId      Compartment ID
 * @param region             Region
 * @param availabilityDomain AD
 * @param tests              Tests to run (e.g. micronaut, micronaut-graal)
 * @param types              Test types to run (e.g. plaintext, db, fortune)
 * @param duration           Duration of each run, TFB defaults to 15 seconds
 */
@ConfigurationProperties("techempower")
public record TeConfiguration(
        int repetitions,
        List<String> tests,
        List<String> types,
        Duration duration
) {
}
