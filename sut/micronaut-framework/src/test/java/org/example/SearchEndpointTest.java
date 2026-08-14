package org.example;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

class SearchEndpointTest {
    @Test
    void findsTheFirstMatchingString() throws Exception {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class)) {
            URI endpoint = server.getURI().resolve("/search/find");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"haystack\":[\"abc\",\"needle here\"],\"needle\":\"needle\"}"))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            Assertions.assertEquals(200, response.statusCode());
            Assertions.assertEquals("{\"listIndex\":1,\"stringIndex\":0}", response.body());
        }
    }
}
