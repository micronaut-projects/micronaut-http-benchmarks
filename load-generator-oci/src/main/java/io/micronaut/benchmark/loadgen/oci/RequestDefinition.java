package io.micronaut.benchmark.loadgen.oci;

import io.hyperfoil.http.api.HttpMethod;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.naming.Named;

public interface RequestDefinition {
    @Bindable(defaultValue = "GET")
    @NonNull
    HttpMethod getMethod();

    @NonNull
    String getUri();

    @Bindable(defaultValue = "example.com")
    @NonNull
    String getHost();

    @Bindable(defaultValue = "application/json")
    @NonNull
    String getRequestType();

    @Nullable
    String getRequestBody();

    interface SampleRequestDefinition extends RequestDefinition, Named {
        @NonNull
        String getResponseBody();

        @Bindable(defaultValue = "true")
        @NonNull
        boolean isResponseJson();
    }
}
