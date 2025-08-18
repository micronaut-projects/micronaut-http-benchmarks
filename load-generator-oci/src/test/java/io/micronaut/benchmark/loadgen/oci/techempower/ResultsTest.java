package io.micronaut.benchmark.loadgen.oci.techempower;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micronaut.http.client.HttpClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResultsTest {
    final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .findAndRegisterModules();

    @Test
    @Disabled // durations are not preserved perfectly
    public void backAndForth() throws Exception {
        Results results = mapper.readValue(TEST_RESULTS_1, Results.class);
        String back = mapper.writeValueAsString(results);
        JsonNode ltree = mapper.readTree(mapper.writeValueAsString(mapper.readValue(TEST_RESULTS_1, Map.class)));
        JsonNode rtree = mapper.readTree(mapper.writeValueAsString(mapper.readValue(back, Map.class)));
        assertEquals(ltree, rtree);
    }

    @Test
    public void merge() throws Exception {
        Results r1 = mapper.readValue(TEST_RESULTS_1, Results.class);
        Results r2 = mapper.readValue(TEST_RESULTS_2, Results.class);
        Results merged = Results.merge(List.of(r1, r2));
        assertEquals(r1.additionalProperties(), merged.additionalProperties());
        assertEquals(r1.testMetadata(), merged.testMetadata());
        System.out.println(merged.rawData().results().get("plaintext").get("micronaut").getFirst());
        assertEquals(Duration.ofNanos(1_740_000), merged.rawData().results().get("plaintext").get("micronaut").getFirst().latencyAvg());
        assertEquals(Duration.ofMillis(62), merged.rawData().results().get("plaintext").get("micronaut").getFirst().latencyMax());
        assertEquals(Duration.ZERO, merged.rawData().results().get("plaintext").get("micronaut").getFirst().latencyStdev());
        assertEquals(1755250268, merged.rawData().results().get("plaintext").get("micronaut").getFirst().startTime());
        assertEquals(1755250328 - 1755250268 + 1755250328, merged.rawData().results().get("plaintext").get("micronaut").getFirst().endTime());
    }

    public static void main(String[] args) throws Exception {
        List<Results> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Path ourResultDir;
            try (Stream<Path> list = Files.list(Path.of("techempower-output", String.valueOf(i), "results"))) {
                ourResultDir = list.max(Comparator.comparing(p -> {
                    try {
                        return Files.getLastModifiedTime(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })).orElseThrow();
            }
            byte[] resultBytes = Files.readAllBytes(ourResultDir.resolve("results.json"));
            results.add(new ObjectMapper().readValue(resultBytes, Results.class));
        }
        Results merged = Results.merge(results);
        System.out.println(TeInfrastructure.uploadResults(HttpClient.create(new URL("https://tfb-status.techempower.com")), merged));;
    }

    private static final String TEST_RESULTS_1 = """
            {
              "uuid": "059e9dad-819f-438b-85af-87f8e9f64bcf",
              "name": "yawkat/TE-FrameworkBenchmarks:loom-benchmarks-jdbc ",
              "environmentDescription": "server (VM.Standard.E4.Flex, 8.0 cores, 64.0G) database (VM.Standard.E4.Flex, 16.0 cores, 32.0G) client (VM.Standard.E4.Flex, 16.0 cores, 32.0G) ",
              "git": null,
              "startTime": 1755249523591,
              "completionTime": 1755254519456,
              "concurrencyLevels": [
                16,
                32,
                64,
                128,
                256,
                512
              ],
              "pipelineConcurrencyLevels": [
                256,
                1024,
                4096,
                16384
              ],
              "queryIntervals": [
                1,
                5,
                10,
                15,
                20
              ],
              "cachedQueryIntervals": [
                1,
                10,
                20,
                50,
                100
              ],
              "frameworks": [
                "micronaut"
              ],
              "duration": "60",
              "rawData": {
                "cached-query": {},
                "db": {},
                "fortune": {},
                "json": {},
                "plaintext": {
                  "micronaut": [
                    {
                      "latencyAvg": "1.48ms",
                      "latencyStdev": "1.25ms",
                      "latencyMax": "60.82ms",
                      "totalRequests": 93131488,
                      "startTime": 1755250268,
                      "endTime": 1755250328
                    },
                    {
                      "latencyAvg": "5.92ms",
                      "latencyStdev": "3.39ms",
                      "latencyMax": "226.95ms",
                      "totalRequests": 93890400,
                      "startTime": 1755250330,
                      "endTime": 1755250390
                    },
                    {
                      "latencyAvg": "536.01ms",
                      "latencyStdev": "661.71ms",
                      "latencyMax": "4.23s",
                      "totalRequests": 61367340,
                      "startTime": 1755250392,
                      "endTime": 1755250452
                    },
                    {
                      "latencyAvg": "1.00s",
                      "latencyStdev": "984.67ms",
                      "latencyMax": "5.92s",
                      "totalRequests": 45488508,
                      "startTime": 1755250454,
                      "endTime": 1755250515
                    }
                  ]
                },
                "query": {},
                "update": {},
                "commitCounts": {
                  "micronaut": 0
                },
                "slocCounts": {
                  "micronaut": 2545
                }
              },
              "completed": {
                "micronaut": "20250815093517"
              },
              "succeeded": {
                "cached-query": [],
                "db": [
                  "micronaut"
                ],
                "fortune": [],
                "json": [],
                "plaintext": [
                  "micronaut"
                ],
                "query": [],
                "update": []
              },
              "failed": {
                "cached-query": [],
                "db": [],
                "fortune": [],
                "json": [],
                "plaintext": [],
                "query": [],
                "update": []
              },
              "verify": {
                "micronaut": {
                  "db": "pass",
                  "plaintext": "pass"
                }
              },
              "testMetadata": [
                {
                  "project_name": "0http",
                  "name": "0http",
                  "approach": "realistic",
                  "classification": "platform",
                  "database": "none",
                  "framework": "0http",
                  "language": "javascript",
                  "orm": "raw",
                  "platform": "nodejs",
                  "webserver": "none",
                  "os": "linux",
                  "database_os": "linux",
                  "display_name": "0http",
                  "notes": "",
                  "versus": "nodejs",
                  "tags": []
                },
                {
                  "project_name": "micronaut",
                  "name": "micronaut",
                  "approach": "realistic",
                  "classification": "micro",
                  "database": "postgres",
                  "framework": "micronaut",
                  "language": "java",
                  "orm": "raw",
                  "platform": "netty",
                  "webserver": "netty",
                  "os": "linux",
                  "database_os": "linux",
                  "display_name": "Micronaut [Vertx PG Client]",
                  "notes": "",
                  "versus": "None",
                  "tags": []
                }
              ]
            }
            """;
    private static final String TEST_RESULTS_2 = """
            {
              "rawData": {
                "cached-query": {},
                "db": {},
                "fortune": {},
                "json": {},
                "plaintext": {
                  "micronaut": [
                    {
                      "latencyAvg": "2ms",
                      "latencyStdev": "3ms",
                      "latencyMax": "62ms",
                      "totalRequests": 93131488,
                      "startTime": 1755250268,
                      "endTime": 1755250328
                    },
                    {
                      "latencyAvg": "5.92ms",
                      "latencyStdev": "3.39ms",
                      "latencyMax": "226.95ms",
                      "totalRequests": 93890400,
                      "startTime": 1755250330,
                      "endTime": 1755250390
                    },
                    {
                      "latencyAvg": "536.01ms",
                      "latencyStdev": "661.71ms",
                      "latencyMax": "4.23s",
                      "totalRequests": 61367340,
                      "startTime": 1755250392,
                      "endTime": 1755250452
                    },
                    {
                      "latencyAvg": "1.00s",
                      "latencyStdev": "984.67ms",
                      "latencyMax": "5.92s",
                      "totalRequests": 45488508,
                      "startTime": 1755250454,
                      "endTime": 1755250515
                    }
                  ]
                },
                "query": {},
                "update": {},
                "commitCounts": {
                  "micronaut": 0
                },
                "slocCounts": {
                  "micronaut": 2545
                }
              }
            }
            """;
}