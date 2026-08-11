package io.micronaut.benchmark.loadgen.oci;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestDefinitionTest {
    @Test
    public void deserialize() throws JacksonException {
        RequestDefinition.SampleRequestDefinition input = (RequestDefinition.SampleRequestDefinition) Proxy.newProxyInstance(
                RequestDefinitionTest.class.getClassLoader(),
                new Class[]{RequestDefinition.SampleRequestDefinition.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getResponseMatchingMode")) {
                        return RequestDefinition.SampleRequestDefinition.MatchingMode.REGEX;
                    }
                    if (method.getName().equals("getRequestBody")) {
                        return "foo";
                    }
                    return null;
                }
        );
        JsonMapper mapper = JsonMapper.builder().build();
        String json = mapper.writeValueAsString(input);
        for (Class<? extends RequestDefinition> c : Set.of(
                RequestDefinition.class,
                RequestDefinition.SampleRequestDefinition.class
        )) {
            RequestDefinition deser = mapper.readValue(json, RequestDefinition.class);
            assertEquals("foo", deser.getRequestBody());
            assertEquals(RequestDefinition.SampleRequestDefinition.MatchingMode.REGEX, ((RequestDefinition.SampleRequestDefinition) deser).getResponseMatchingMode());
        }
    }
}
