package io.micronaut.benchmark.loadgen.oci;

import io.hyperfoil.http.api.HttpMethod;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.naming.Named;

/**
 * Configuration properties for an HTTP request to make.
 */
public interface RequestDefinition {
    /**
     * Request method.
     */
    @Bindable(defaultValue = "GET")
    @NonNull
    HttpMethod getMethod();

    /**
     * Request path (relative URI).
     */
    @NonNull
    String getUri();

    /**
     * Request host header.
     */
    @Bindable(defaultValue = "example.com")
    @NonNull
    String getHost();

    /**
     * Request content-type header. Only used if {@link #getRequestBody()} is also set.
     */
    @Bindable(defaultValue = "application/json")
    @NonNull
    String getRequestType();

    /**
     * Optional request body.
     */
    @Nullable
    String getRequestBody();

    /**
     * Request definition for a benchmark sample request. In particular, this adds a fixed expected response.
     */
    interface SampleRequestDefinition extends RequestDefinition, Named {
        /**
         * The expected response body.
         */
        @NonNull
        String getResponseBody();

        /**
         * The matching mode to use for verifying the response body.
         */
        @Bindable(defaultValue = "JSON")
        @NonNull
        MatchingMode getResponseMatchingMode();

        enum MatchingMode {
            /**
             * Body must match exactly.
             */
            EQUAL,
            /**
             * Body must represent the same JSON (whitespace differences are allowed).
             */
            JSON,
            /**
             * {@link #getResponseBody()} is a regex and the response body must match that regex.
             */
            REGEX
        }
    }
}
