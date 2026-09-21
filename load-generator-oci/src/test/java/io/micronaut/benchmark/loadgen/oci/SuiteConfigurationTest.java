package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SuiteConfigurationTest {
    @Test
    void bindsOneLocationWithoutRepetitionSettings() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "suite.name", "loop",
                "suite.location.compartment-id", "benchmark-compartment",
                "suite.location.region", "eu-frankfurt-1",
                "suite.location.availability-domain", "benchmark-ad"
        ), "test")) {
            assertEquals(new SuiteRunner.SuiteConfiguration("loop"), context.getBean(SuiteRunner.SuiteConfiguration.class));
            assertEquals(1, context.getBeansOfType(OciLocation.class).size());
            assertEquals(new OciLocation("benchmark-compartment", "eu-frankfurt-1", "benchmark-ad"),
                    context.getBean(OciLocation.class));
        }
    }
}
