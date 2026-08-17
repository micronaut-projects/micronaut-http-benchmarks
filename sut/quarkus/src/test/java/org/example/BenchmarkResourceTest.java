package org.example;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;

@QuarkusTest
class BenchmarkResourceTest {
    @Test
    void returnsStatusObject() {
        // Given

        // When
        var response = given()
                .when().get("/status");

        // Then
        response.then()
                .statusCode(200)
                .body("$", aMapWithSize(1))
                .body("serverSocketChannelImplementation", matchesPattern("(?i).*io_uring.*|.*iouring.*"));
    }

    @Test
    void returnsFirstMatchingIndices() {
        // Given
        var requestBody = """
                {"haystack":["ssxvnj","hpdqdx","vcrast","vybcwv","mgnykr","xvzxkg"],"needle":"bcw"}
                """;

        // When
        var response = given()
                .contentType("application/json")
                .body(requestBody)
                .when().post("/search/find");

        // Then
        response.then()
                .statusCode(200)
                .body(is("{\"listIndex\":3,\"stringIndex\":2}"));
    }

    @Test
    void returnsNotFoundWhenNeedleIsAbsent() {
        // Given
        var requestBody = """
                {"haystack":["abc"],"needle":"z"}
                """;

        // When
        var response = given()
                .contentType("application/json")
                .body(requestBody)
                .when().post("/search/find");

        // Then
        response.then()
                .statusCode(404)
                .body(is(""));
    }
}
