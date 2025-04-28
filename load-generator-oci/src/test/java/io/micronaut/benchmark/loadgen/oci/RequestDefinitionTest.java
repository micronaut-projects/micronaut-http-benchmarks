package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestDefinitionTest {
    @Test
    public void deserialize() throws JsonProcessingException {
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