package io.micronaut.benchmark.api;

import io.hyperfoil.http.api.HttpMethod;
import io.micronaut.core.annotation.Nullable;

import java.util.Map;
import java.util.Objects;

public record SuiteRequest(
        String name,
        HttpMethod method,
        String uri,
        String host,
        String requestType,
        Map<String, String> requestHeaders,
        @Nullable String requestBody,
        @Nullable String responseBody,
        MatchingMode responseMatchingMode
) {
    public SuiteRequest {
        name = Objects.requireNonNull(name);
        method = method == null ? HttpMethod.GET : method;
        uri = Objects.requireNonNull(uri);
        host = host == null ? "example.com" : host;
        requestType = requestType == null ? "application/json" : requestType;
        requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
        responseMatchingMode = responseMatchingMode == null ? MatchingMode.JSON : responseMatchingMode;
    }

    public enum MatchingMode {
        EQUAL,
        JSON,
        REGEX
    }
}
