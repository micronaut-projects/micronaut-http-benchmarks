package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.hyperfoil.http.api.HttpMethod;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.naming.Named;
import io.micronaut.core.util.StringUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Configuration properties for an HTTP request to make.
 */
@JsonDeserialize(using = RequestDefinition.Deser.class)
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

    @NonNull
    Map<String, String> getRequestHeaders();

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

    class Deser extends JsonDeserializer<RequestDefinition> {
        private static final Set<Class<?>> INTERFACES = Set.of(
                RequestDefinition.class,
                SampleRequestDefinition.class,
                HyperfoilRunner.HyperfoilConfiguration.StatusRequest.class
        );

        @Override
        public RequestDefinition deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
            if (!p.isExpectedStartObjectToken()) {
                return (RequestDefinition) ctxt.handleUnexpectedToken(RequestDefinition.class, p);
            }
            Map<Method, Object> values = new HashMap<>();
            while (true) {
                String name = p.nextFieldName();
                if (name == null) {
                    break;
                }
                Method method = null;
                for (Class<?> itf : INTERFACES) {
                    for (Method candidate : itf.getDeclaredMethods()) {
                        if (candidate.getName().equals("get" + StringUtils.capitalize(name)) || candidate.getName().equals("is" + StringUtils.capitalize(name))) {
                            method = candidate;
                        }
                    }
                }
                p.nextToken();
                if (method == null) {
                    p.skipChildren();
                    continue;
                }
                Object value;
                if (p.currentToken() == JsonToken.VALUE_NULL) {
                    value = null;
                } else {
                    JavaType type = ctxt.getTypeFactory().constructType(method.getGenericReturnType());
                    value = ctxt.findContextualValueDeserializer(type, new BeanProperty.Bogus()).deserialize(p, ctxt);
                }
                values.put(method, value);
            }
            return (RequestDefinition) Proxy.newProxyInstance(
                    RequestDefinition.class.getClassLoader(),
                    INTERFACES.toArray(new Class[0]),
                    (proxy, method, args) -> {
                        if (method.getName().equals("equals")) {
                            return args[0] == proxy;
                        } else if (method.getName().equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        }
                        return values.get(method);
                    }
            );
        }
    }
}
