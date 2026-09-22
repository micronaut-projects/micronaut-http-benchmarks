# Querying benchmark profiles

`bench profile` runs the pinned Nix-packaged [jfr-query](https://github.com/parttimenerd/jfr-query)
CLI against a saved run. It imports JFR events into DuckDB and leaves analysis to SQL. It does not
start the benchmark daemon or submit another benchmark.

```sh
./bench profile output/runs/RUN_ID
./bench profile output/runs/RUN_ID --context > schema.txt
./bench profile output/runs/RUN_ID --query 'SELECT * FROM benchmark_phases'
./bench profile output/runs/RUN_ID --query-file investigation.sql --csv
```

The default invocation prints the database and executable paths as JSON. `--context` describes the
actual imported tables, columns, views, and macros, including custom event types. Use that schema
when writing queries. The upstream CLI can also be invoked directly:

```sh
nix run ./nix#jfr-query -- --help
nix run ./nix#jfr-query -- query /absolute/path/to/profile.db 'hot-methods'
```

The package includes `import`, `query`, `context`, `views`, and `macros`; the browser frontend is
not packaged. It is built from a pinned source revision because the published snapshot contains
an older application. The Nix package restores the upstream parent POM and fixes the JDBC adapter's
null, array, and timestamp calls for its declared DuckDB version.

## Measurement boundaries

**Imported events and upstream named views cover the full recording.** Apply
`WHERE benchmark_measured(startTime)` to select the union of `main/` measurement intervals.
The macro uses UTC, half-open intervals: start inclusive, end exclusive. It excludes warmup and
shutdown without changing the original event tables. To examine one phase, join against
`benchmark_phases` using its `start_time` and `end_time` columns.

The database also contains:

- `benchmark_phases`: phase names, measurement flags, start/end timestamps, request/response counts,
  and duration in seconds.
- `benchmark_run`: run ID, profile coverage, original metadata as JSON, SLA warnings as JSON, and
  machine information text. Execution success does not imply the absence of SLA warnings.

## Example investigations

Average measured CPU load, using JDK CPU-load events rather than CPU sample percentages:

```sql
SELECT avg(jvmUser) AS cpu_user,
       avg(jvmSystem) AS cpu_system,
       avg(jvmUser) + avg(jvmSystem) AS cpu_total
FROM CPULoad
WHERE benchmark_measured(startTime);
```

JDK CPU load is a fraction normalized across the processors available to that JVM. Check the
recording and machine metadata before converting this to CPU time per response.

Allocation estimates by class, with the allocation sample weights retained:

```sql
SELECT c.javaName, sum(a.weight) AS estimated_bytes
FROM ObjectAllocationSample a
JOIN Class c ON c._id = a.objectClass
WHERE benchmark_measured(a.startTime)
GROUP BY c.javaName
ORDER BY estimated_bytes DESC
LIMIT 20;
```

Count samples containing a particular method anywhere in the stack. `EXISTS` counts each event
once even when the method appears more than once. Edit the class and method names for the question
being investigated:

```sql
SELECT count(*) AS samples
FROM ExecutionSample e
WHERE benchmark_measured(e.startTime)
  AND EXISTS (
    SELECT 1
    FROM Method m JOIN Class c ON c._id = m.type
    WHERE c.javaName = 'io.micronaut.serde.support.DefaultSerdeRegistry$SpecificBeanDeserializer'
      AND m.name = 'createSpecific'
      AND list_contains(e."stackTrace$methods", m._id)
  );
```

This is inclusive attribution; the upstream `hot-methods` view groups by the top frame instead.
The example counts events, which matches the execution samples in the validated recordings. Check
the schema and use sample weights if a different profiler event represents multiple samples.
Inclusive percentages overlap and should not be summed into an exclusive CPU breakdown.

Check whether the imported CPU stacks were truncated:

```sql
SELECT count(*) AS samples,
       count(*) FILTER (WHERE "stackTrace$truncated") AS truncated_samples,
       max("stackTrace$length") AS maximum_recorded_depth
FROM ExecutionSample
WHERE benchmark_measured(startTime);
```

The importer retains 256 frames by default instead of upstream's ten. Override with
`--stack-depth N` (1–4096). A larger import cannot recover frames absent from the recording.
The upstream representation stores method identities, not frame types, line numbers, or bytecode
indices; keep the original JFR/flamegraphs for those details.

## Cache and supported inputs

The database lives in `RUN_DIR/profile-query/profile.db`. Its manifest includes the Nix executable
path, stack depth, adapter version, and SHA-256 hashes of the recording and benchmark context files.
Changes trigger a new import. Imports are locked and staged; a failed import leaves the preceding
complete cache intact. Derived queries and views can be added with SQL, but an invalidated cache
is rebuilt from the recording: keep reusable SQL in separate files.

The command currently accepts successful runs with an existing declared `.jfr` artifact. The
native perf and Python py-spy paths remain available through `bench plot`. `--flake` and
`--override-input` select the analysis tool package just as they select inputs for other CLI commands.

To work on the CLI itself in a worktree, build with `./gradlew :benchmark-cli:installDist` and invoke
`benchmark-cli/build/install/benchmark-cli/bin/benchmark-cli` for offline validation. The `./bench`
wrapper continues to use the main checkout's installed CLI and daemon.

## Validation

`ProfileQueryTest` imports a real generated JFR through the Nix package and checks deep stacks,
measurement filtering, schema discovery, SQL failures, cache reuse, invalidation, and preservation
of the previous cache after a failed import. Run it with:

```sh
./gradlew :benchmark-cli:test
```

The integration was also checked against the original 2026-09-22 serialization trial recordings:

| Main-phase measurement | Baseline `3c3d65e3` | Cache patch `6effbd3c` |
|---|---:|---:|
| CPU samples | 34,613 | 31,869 |
| Samples containing deserializer specialization | 2,624 | 0 |
| Estimated allocation bytes | 66,274,387,912 | 60,265,320,688 |

These counts and allocation totals match the independent analysis used in the performance report.
