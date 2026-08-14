package io.micronaut.benchmark.loadgen.oci;

import io.hyperfoil.http.api.HttpMethod;
import io.micronaut.core.annotation.Nullable;

import java.util.Map;

public record SuiteRequest(
        String name,
        HttpMethod method,
        String uri,
        String host,
        String requestType,
        Map<String, String> requestHeaders,
        @Nullable String requestBody,
        String responseBody,
        RequestDefinition.SampleRequestDefinition.MatchingMode responseMatchingMode
) implements RequestDefinition.SampleRequestDefinition {
    public SuiteRequest {
        method = method == null ? HttpMethod.GET : method;
        host = host == null ? "example.com" : host;
        requestType = requestType == null ? "application/json" : requestType;
        requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
        responseMatchingMode = responseMatchingMode == null ? MatchingMode.JSON : responseMatchingMode;
    }

    @Override public HttpMethod getMethod() { return method; }
    @Override public String getUri() { return uri; }
    @Override public String getHost() { return host; }
    @Override public String getRequestType() { return requestType; }
    @Override public Map<String, String> getRequestHeaders() { return requestHeaders; }
    @Override public String getRequestBody() { return requestBody; }
    @Override public String getResponseBody() { return responseBody; }
    @Override public MatchingMode getResponseMatchingMode() { return responseMatchingMode; }
    @Override public String getName() { return name; }
}
