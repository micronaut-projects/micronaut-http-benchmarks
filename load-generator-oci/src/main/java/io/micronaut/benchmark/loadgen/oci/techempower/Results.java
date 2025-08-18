package io.micronaut.benchmark.loadgen.oci.techempower;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record Results(
        RawData rawData,
        List<TestMetadata> testMetadata,
        @JsonAnyGetter @JsonAnySetter Map<String, Object> additionalProperties
) {
    public static Results merge(List<Results> results) {
        return new Results(
                RawData.merge(results.stream().map(r -> r.rawData).toList()),
                results.getFirst().testMetadata(),
                results.getFirst().additionalProperties()
        );
    }

    public record TestMetadata(
            String name,
            @JsonAnyGetter @JsonAnySetter Map<String, Object> additionalProperties
    ) {
    }

    public record RawData(
            Map<String, Integer> commitCounts,
            Map<String, Integer> slocCounts,
            @JsonAnyGetter @JsonAnySetter Map<String, Map<String, List<RunResult>>> results
    ) {
        public static RawData merge(List<RawData> rawData) {
            Map<String, Map<String, List<RunResult>>> firstResults = rawData.getFirst().results;
            Map<String, Map<String, List<RunResult>>> results = new HashMap<>();
            for (String type : firstResults.keySet()) {
                Map<String, List<RunResult>> forType = new HashMap<>();
                for (String test : firstResults.get(type).keySet()) {
                    List<RunResult> forTest = new ArrayList<>();
                    for (int i = 0; i < firstResults.get(type).get(test).size(); i++) {
                        int finalI = i;
                        forTest.add(RunResult.merge(rawData.stream().map(rd -> rd.results.get(type).get(test).get(finalI)).toList()));
                    }
                    forType.put(test, forTest);
                }
                results.put(type, forType);
            }
            return new RawData(
                    rawData.getFirst().commitCounts(),
                    rawData.getFirst().slocCounts(),
                    results
            );
        }
    }

    public record RunResult(
            @JsonSerialize(using = DurationSerializer.class) @JsonDeserialize(using = DurationDeserializer.class) Duration latencyAvg,
            @JsonSerialize(using = DurationSerializer.class) @JsonDeserialize(using = DurationDeserializer.class) Duration latencyStdev,
            @JsonSerialize(using = DurationSerializer.class) @JsonDeserialize(using = DurationDeserializer.class) Duration latencyMax,
            long totalRequests,
            long startTime,
            long endTime,
            long connect,
            long read,
            long write,
            long timeout
    ) {
        static RunResult merge(List<RunResult> results) {
            long startTime = results.getFirst().startTime();
            long totalRequests = results.stream().mapToLong(r -> r.totalRequests).sum();
            return new RunResult(
                    Duration.ofNanos(results.stream().mapToLong(r -> r.totalRequests == 0 ? 0 : r.latencyAvg.toNanos() * r.totalRequests).sum() / totalRequests),
                    Duration.ZERO,
                    Duration.ofNanos(results.stream().mapToLong(r -> r.latencyMax == null ? 0 : r.latencyMax.toNanos()).max().getAsLong()),
                    totalRequests,
                    startTime,
                    startTime + results.stream().mapToLong(r -> r.endTime - r.startTime).sum(),
                    results.stream().mapToLong(r -> r.connect).sum(),
                    results.stream().mapToLong(r -> r.read).sum(),
                    results.stream().mapToLong(r -> r.write).sum(),
                    results.stream().mapToLong(r -> r.timeout).sum()
            );
        }
    }

    static final class DurationDeserializer extends JsonDeserializer<Duration> {
        @Override
        public Duration deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
            String s = p.getValueAsString();
            int shift = 0;
            for (String suffix : List.of("ns", "us", "ms", "s")) {
                if (s.endsWith(suffix)) {
                    s = s.substring(0, s.length() - suffix.length());
                    return Duration.ofNanos(new BigDecimal(s).scaleByPowerOfTen(shift).longValueExact());
                }
                shift += 3;
            }
            throw ctxt.weirdStringException(s, Duration.class, "No suffix found");
        }
    }

    static final class DurationSerializer extends JsonSerializer<Duration> {
        @Override
        public void serialize(Duration value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            BigDecimal decimal = new BigDecimal(value.toNanos());
            String s;
            if (value.toSeconds() > 0) {
                s = decimal.scaleByPowerOfTen(-9).toPlainString() + "s";
            } else if (value.toMillis() > 0) {
                s = decimal.scaleByPowerOfTen(-6).toPlainString() + "ms";
            } else if (value.toNanos() > 1000) {
                s = decimal.scaleByPowerOfTen(-3).toPlainString() + "us";
            } else {
                s = decimal.toPlainString() + "ns";
            }
            gen.writeString(s);
        }
    }
}
