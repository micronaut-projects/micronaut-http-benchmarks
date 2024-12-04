package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.EachProperty;

/**
 * An OCI location to test on.
 *
 * @param compartmentId      The main compartment ID
 * @param region             The region
 * @param availabilityDomain The AD within the region
 */
@EachProperty(value = "suite.location", list = true)
public record OciLocation(
        String compartmentId,
        String region,
        String availabilityDomain
) {
}
