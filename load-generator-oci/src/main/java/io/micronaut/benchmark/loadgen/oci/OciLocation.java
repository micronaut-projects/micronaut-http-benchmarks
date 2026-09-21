package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * An OCI location to test on.
 *
 * @param compartmentId      The main compartment ID
 * @param region             The region
 * @param availabilityDomain The AD within the region
 */
@ConfigurationProperties("suite.location")
public record OciLocation(
        String compartmentId,
        String region,
        String availabilityDomain
) {
}
