package io.micronaut.benchmark.loadgen.oci.techempower;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.core.annotation.Nullable;

@EachProperty(value = "techempower.revision", list = true)
public record Revision(
        String githubRepoName,
        @Nullable
        String modulePrefix,
        String ref
) {
    String folderName() {
        return githubRepoName.replace('/', '_');
    }
}
