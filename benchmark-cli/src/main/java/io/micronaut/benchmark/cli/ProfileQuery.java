package io.micronaut.benchmark.cli;

import io.micronaut.benchmark.api.Nix;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Offline adapter: jfr-query owns profile decoding and SQL; we supply experiment context. */
final class ProfileQuery {
    private final Path tool;

    ProfileQuery(Path tool) {
        this.tool = tool;
    }

    static Path buildTool(Nix nix, String flake, Map<String, String> inputs) throws Exception {
        String reference = Files.isDirectory(Path.of(flake)) ? Path.of(flake).toAbsolutePath().normalize().toString() : flake;
        var args = new ArrayList<>(List.of("build", reference + "#jfr-query", "--json", "--no-link", "--no-write-lock-file"));
        inputs.forEach((name, value) -> args.addAll(List.of("--override-input", name, value)));
        return Nix.checkStorePath(nix.json(args, System.err).get(0).get("outputs").get("out").asString(), false)
                .resolve("bin/jfr-query");
    }

    Path prepare(Path directory, int stackDepth) throws Exception {
        if (stackDepth < 1 || stackDepth > 4096) {
            throw new IllegalArgumentException("Stack depth must be between 1 and 4096");
        }
        directory = directory.toAbsolutePath().normalize();
        JsonNode metadata = Bench.JSON.readTree(directory.resolve("metadata.json").toFile());
        String artifact = metadata.path("profiling").path("artifact").asString("");
        Path profile = directory.resolve(artifact).normalize();
        if (!profile.startsWith(directory) || !artifact.endsWith(".jfr") || !Files.isRegularFile(profile)) {
            throw new IllegalArgumentException("Run must declare an existing JFR profiling artifact; perf and py-spy recordings are not supported by this command");
        }
        JsonNode run = Bench.JSON.readTree(directory.resolve("run.json").toFile());
        if (!"SUCCEEDED".equals(run.path("state").asString())) {
            throw new IllegalArgumentException("Profile import requires a completed, successful run");
        }
        JsonNode output = Bench.JSON.readTree(directory.resolve("output.json").toFile());
        Path cache = directory.resolve("profile-query");
        Files.createDirectories(cache);
        Path database = cache.resolve("profile.db");
        Path manifest = cache.resolve("manifest.json");
        try (var channel = FileChannel.open(cache.resolve("import.lock"), CREATE, WRITE);
             var ignored = channel.lock()) {
            var hashes = new LinkedHashMap<String, String>();
            for (String file : List.of(artifact, "metadata.json", "run.json", "output.json", "machine-info.txt")) {
                if (Files.isRegularFile(directory.resolve(file))) {
                    hashes.put(file, sha256(directory.resolve(file)));
                }
            }
            JsonNode fingerprint = Bench.JSON.valueToTree(Map.of("version", 1, "tool", tool.toString(),
                    "stackDepth", stackDepth, "files", hashes));
            if (Files.isRegularFile(database) && Files.isRegularFile(manifest)
                    && fingerprint.equals(Bench.JSON.readTree(manifest.toFile()))) {
                return database;
            }
            // A failed/interrupted import must never become a reusable cache entry.
            Path staging = Files.createTempDirectory(cache, "import-");
            try {
                Path temporary = staging.resolve("profile.db");
                execute(List.of("import", "--stacktrace-depth", Integer.toString(stackDepth), profile.toString(), temporary.toString()), System.err);
                execute(List.of("query", temporary.toString(), contextSql(directory, run, metadata, output)), OutputStream.nullOutputStream());
                // Never leave an old fingerprint describing a newly published database.
                Files.deleteIfExists(manifest);
                Files.move(temporary, database, ATOMIC_MOVE, REPLACE_EXISTING);
                Path temporaryManifest = staging.resolve("manifest.json");
                Bench.JSON.writerWithDefaultPrettyPrinter().writeValue(temporaryManifest.toFile(), fingerprint);
                Files.move(temporaryManifest, manifest, ATOMIC_MOVE, REPLACE_EXISTING);
            } finally {
                try (var files = Files.walk(staging)) {
                    for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
        return database;
    }

    void query(Path database, String query, boolean csv, OutputStream output) throws Exception {
        var args = new ArrayList<>(List.of("query", database.toString(), query));
        if (csv) args.add("--csv");
        execute(args, output);
    }

    void context(Path database, OutputStream output) throws Exception {
        execute(List.of("context", database.toString()), output);
    }

    private void execute(List<String> args, OutputStream output) throws Exception {
        var command = new ArrayList<>(List.of(tool.toString()));
        command.addAll(args);
        try {
            Nix.run(new ProcessBuilder(command), output, System.err);
        } catch (IOException e) {
            throw new IOException("jfr-query failed: " + e.getMessage(), e);
        }
    }

    private static String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = new DigestInputStream(Files.newInputStream(path), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String contextSql(Path directory, JsonNode run, JsonNode metadata, JsonNode output) throws IOException {
        var sql = new StringBuilder("""
                CREATE TABLE benchmark_phases (
                  name VARCHAR, measurement BOOLEAN, start_time TIMESTAMP, end_time TIMESTAMP,
                  request_count BIGINT, response_count BIGINT, duration_seconds DOUBLE
                );
                COMMENT ON TABLE benchmark_phases IS 'Hyperfoil UTC phase bounds. Intervals are [start_time, end_time); measurement excludes warmup.';
                """);
        int measurements = 0;
        for (JsonNode phase : output.path("stats")) {
            String name = phase.path("name").asString();
            JsonNode summary = phase.path("total").path("summary");
            long start = summary.path("startTime").asLong();
            long end = summary.path("endTime").asLong();
            if (name == null || end <= start) throw new IllegalArgumentException("Missing or invalid benchmark phase bounds");
            boolean measurement = name.startsWith("main/");
            if (measurement) measurements++;
            sql.append("INSERT INTO benchmark_phases VALUES (").append(sqlString(name)).append(',').append(measurement)
                    .append(", epoch_ms(").append(start).append("), epoch_ms(").append(end).append("), ")
                    .append(summary.path("requestCount").asLong()).append(',').append(summary.path("responseCount").asLong())
                    .append(',').append((end - start) / 1000.0).append(");\n");
        }
        if (measurements == 0) throw new IllegalArgumentException("Run contains no main measurement phases");
        Path machineInfo = directory.resolve("machine-info.txt");
        sql.append("CREATE TABLE benchmark_run AS SELECT ")
                .append(sqlString(run.path("id").asString())).append(" AS run_id, ")
                .append(sqlString(metadata.path("profileCoverage").asString("unknown"))).append(" AS profile_coverage, ")
                .append(sqlString(metadata.toString())).append("::JSON AS metadata, ")
                .append(sqlString(output.hasNonNull("failures") ? output.get("failures").toString() : "[]")).append("::JSON AS sla_failures, ")
                .append(sqlString(Files.exists(machineInfo) ? Files.readString(machineInfo) : "")).append(" AS machine_info;\n")
                .append("COMMENT ON TABLE benchmark_run IS 'Saved experiment metadata and SLA warnings. Imported profile tables cover the full recording; filter explicitly using benchmark_measured(startTime).';\n")
                .append("CREATE MACRO benchmark_measured(t) AS EXISTS (SELECT 1 FROM benchmark_phases p WHERE p.measurement AND t >= p.start_time AND t < p.end_time);\n")
                .append("COMMENT ON MACRO benchmark_measured IS 'True during any main measurement phase; excludes warmup and shutdown. UTC, half-open intervals.';\n")
                .append("SELECT count(*) AS measurement_phases FROM benchmark_phases WHERE measurement;");
        return sql.toString();
    }
}
