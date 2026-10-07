# Micronaut HTTP server benchmarks

Suites, workloads, and deployable NixOS systems are defined in `nix/`. The Micronaut CLI resolves experiment
derivations; the Micronaut daemon executes them on warm OCI infrastructure.

All Micronaut Framework benchmarks use Micronaut Serialization for JSON encoding and decoding.

## Usage

Both applications require Java 25 and Nix with flakes enabled.

```sh
./gradlew :benchmark-cli:installDist :load-generator-oci:installDist
alias bench="$PWD/benchmark-cli/build/install/benchmark-cli/bin/benchmark-cli"
bench cases
bench run --suite standard --run pure-netty --protocol https2 --document 6-6 \
  --wait
```

Focused runs default to a quick adaptive throughput search. Suites default to the thorough preset. Each repetition
discovers a rate range, resets the SUT, warms it up again, and validates with an ascending sweep. Results after the first
failed phase are excluded even if later traffic succeeds. See [throughput methodology](docs/throughput.md).

| Setting | Quick | Thorough |
| --- | ---: | ---: |
| Warmup before each stage | 60s | 180s |
| Discovery phase / increase | 10s / 25% | 15s / 25% |
| Validation phase / increase | 15s / 5% | 45s / 2% |
| Repetitions | 1 | 2 |

Use `--preset thorough` for focused comparisons. `--start-rate`, `--max-rate`, `--repetitions`, `--discovery-duration`,
`--discovery-step`, and `--validation-step` override search settings. Steps are percentages. `--warmup` overrides both
warmups and `--duration` overrides validation duration. The default ceiling is 300,000 RPS.
Discovery inserts a five-second smooth ramp between its fixed-rate measurements. Use `--discovery-ramp-duration`
to change it, or `--discovery-ramp-duration 0s` for direct rate changes. Ramp passes do not establish passing throughput;
ramp failures stop discovery and retain the preceding passing measurement for validation.
Hyperfoil preallocates sessions for every phase. The cluster defaults to four agents with 8 OCPUs and 32 GiB RAM each,
using seven workers and `-Xms25G -Xmx25G` Java heaps per agent. The CPU watchdog samples over 15 seconds.
Use an explicit ceiling that fits the agents; see the [capacity notes](docs/throughput.md#validity-and-interpretation).

An explicit `--rate 1000` retains fixed-rate mode, with 60s warmup and 60s measurement by default.
`--flake` defaults to `nix`, relative to the CLI's working directory;
`--override-input NAME=REFERENCE` can be repeated.

Concurrent agents should use separate worktrees and select their flakes directly:

```sh
bench run --flake /path/to/agent-worktree/nix \
  --suite standard --run pure-netty --protocol https2 --document 6-6
```

Preparation uses normal Nix flake evaluation. Git flakes include dirty tracked files; add new source files to Git before
using them. The CLI does not copy source trees, modify Git, or write lock files. Keep the selected worktree and inputs
unchanged during preparation. Later edits cannot change a resolved derivation.

```sh
bench submit /nix/store/HASH-benchmark-experiment.drv --output out --wait
bench status [RUN_ID]
bench wait RUN_ID
bench wait --batch BATCH_ID
bench cancel RUN_ID
bench stop
```

`submit` accepts an existing derivation and optional repeated `--annotation KEY=VALUE` arguments. Each accepted
submission returns its run ID and absolute result path. Without `--wait`, the CLI returns after acceptance;
disconnecting leaves work queued. Cancellation preserves partial results and attempts collection and bootstrap reset.
Infrastructure startup or reset failure triggers cleanup and stops the daemon.

## Daemon and infrastructure

Commands that need the daemon start it implicitly. It runs from the installation project's root at `127.0.0.1:7075`;
state and infrastructure logs live under `output/daemon`. Logback writes the daemon application log to `output/log`.
After two idle hours it tears down infrastructure and exits.
The benchmark server uses OCI console snapshots during boot, then streams its persistent journal through SSH over
the existing HTTPS relay. The stream replays the retained current boot and resumes from its last delivered journal cursor after
disconnects; an unavailable cursor is reported as a capture failure. Stage markers travel through this stream rather
than the size-limited console snapshots. Early boot output remains in the environment's `benchmark-server.log`, and
journal output outside experiments goes to `benchmark-server-journal.log`. OCI polling for that server pauses after
the journal stream becomes ready, with a final console snapshot collected at teardown. Journal retention on the server
must cover disconnections; cursor recovery does not extend across daemon restarts.
`bench stop` cancels queued/active work, attempts cleanup, and stops the process. Local preparation and analysis
commands do not start it.

Configure OCI credentials, `[suite.location]`, storage buckets, Hyperfoil agent count, and monitoring in
`load-generator-oci/src/main/resources/application.toml` or `MICRONAUT_CONFIG_FILES`. The daemon owns the whole
compartment and cleans it before provisioning and at teardown. Use one daemon per compartment and stop it before
replacing its installed JARs. There is no recovery or adoption after restart.

One daemon owns one infrastructure lifetime. Its settings remain fixed, and experiments requiring a different shape,
kernel, or attachments are rejected. Enable the `loop` or `db` Micronaut environment for nginx or PostgreSQL
attachments. The daemon evaluates
`lib.infrastructure` independently of benchmark suites.

## Results and analysis

`--output-root` defaults to `./output/runs`, relative to the CLI's working directory. Every measurement, including
repeated submissions of the same derivation, gets a fresh UUID directory. Clients may share a root.

Each directory contains `run.json`, experiment metadata and workload, environment/machine information, the existing raw
benchmark output and logs (`output.json`, `server.log`, `agent0.log`, etc.), and declared profiling artifacts.
Completion is recorded only after collection and reset. Failed and cancelled runs retain their available diagnostics.

Adaptive directories contain `search.json` and an incrementally saved `throughput.json`. Raw statistics, effective
workloads, logs, profiles, and eligibility decisions live in `repetitions/N/discovery` and `repetitions/N/validation`.
An expected SLA failure can complete execution successfully: inspect the search outcome separately from `run.json`'s
execution state. Missing, inconclusive, and generator-limited results are retained, not counted as throughput estimates.

`.nix/experiment` links to the built experiment. There is no automatic result deletion. Rerun the derivation recorded in
`run.json` with `submit` while it remains available in the Nix store.

```sh
bench summary output/runs/RUN_ID
bench compare output/runs/BASELINE_ID output/runs/CANDIDATE_ID
bench plot output/runs/RUN_ID
bench plot output/runs
```

Analysis works with the daemon stopped. Summary and comparison emit JSON for measurement phases, excluding warmup.
Plotting reads completed run directories and uses the existing raw profile formats. Current profiles cover the SUT
process lifetime, including warmup and shutdown. Perf conversion uses the tooling retained in `.nix/experiment/perf`.
Uploading requires explicit `plot --upload`.
Adaptive plots show per-repetition bounds and expandable phase diagnostics; later phases are explicitly excluded.

## Profile queries

`bench profile RUN_DIR` imports a saved JFR recording with the pinned Nix-packaged jfr-query CLI
and caches a DuckDB database. Use `--context` to discover the schema, `--query SQL` for exploratory
queries, or `--query-file FILE --csv` for a saved analysis. Add `WHERE benchmark_measured(startTime)`
to restrict event queries to measurement phases; upstream views otherwise cover the full recording.
For adaptive runs select a recording with `--stage 1/validation` or `--stage 2/discovery`. The measurement filter
includes only eligible validation phases; it excludes discovery and all phases at or after failure.

See [profile query documentation](docs/profile-queries.md) for examples, cache behavior, and validation.

## Suites and experiment contract

`bench suite standard --wait` resolves and shuffles all selected cases, then submits an exclusive batch on the daemon's
existing infrastructure. Batches do not replace it. Results use the same per-invocation directory layout and
`--output-root` option. Repetitions run in rounds across cases, shuffled each round, with each discovery/validation pair
kept together. Keep the selected worktree stable while preparation runs.

Benchmark flakes expose `lib.catalog` for discovery and
`lib.mkExperiment { suite; run; protocol; document; rate ? null; preset ? "quick"; search ? {}; warmupDuration ? null; benchmarkDuration ? null; full ? false; }`.
Omitting `rate` selects adaptive mode; `search` accepts the rate bounds, repetitions, discovery duration, and step
overrides described above using camelCase names. Explicit `rate` and legacy `full = true` retain fixed workloads.
The output contains `system`, `hyperfoil.yaml`, `artifacts.json`, `requirements.json`, and opaque `metadata.json`.
Adaptive outputs also contain versioned `search.json`; their YAML contains a two-request response preflight,
warmup, and one measurement-phase template. Body checks run only during preflight; status and transport-error
checks remain active throughout warmup and measurement.
An optional `hyperfoil-data/` directory supplies files referenced by Hyperfoil
`body.fromFile` (paths relative to that directory). Payload files are retained in the Nix closure and uploaded with the
benchmark definition. Perf experiments also retain `perf`.

The daemon accepts `{ derivation, output, outputRoot, annotations }` and builds that derivation without reevaluating the
caller's checkout. It serializes deployment, execution, collection, and bootstrap reset. Artifact entries declare
`remote`, relative `path`, and `directory`; they cannot overwrite runner-owned files or escape the run directory.
Activation and `sut.service` retain their existing conventions.

## Building systems

Existing system outputs remain available:

```sh
cd nix
nix build .#standard-micronaut-system
```

## Profile-guided optimization

The `micronaut-pgo` and `quarkus-pgo` entries in `nix/suites/standard.nix` are opt-in. Uncomment the desired entry, then build its document/protocol-specific system, for example:

```sh
cd nix
nix build .#standard-micronaut-pgo-https2-6-6-system
```

This single build creates an instrumented binary, starts it in a NixOS test VM, trains it, and builds the optimized binary using the resulting `default.iprof`. Each enabled document/protocol pair gets its own optimized system named `${suite}-${run}-${protocol}-${document}-system`. Instrumented binaries are shared between cases of the same framework configuration.

Training uses the suite's request and response definitions and the selected protocol, including TLS. One concurrent user sends requests for two minutes, with one connection and no pipelining. Response validation and request timeouts apply, but throughput and latency targets do not. The service stops gracefully to flush its profile; training failures or a missing profile fail the build.

Profiles and optimized binaries are normal Nix build outputs, reused across suite invocations. No benchmark infrastructure, downloaded profile directory, or separate preparation command is needed. Source, toolchain, training workload, or relevant configuration changes invalidate the corresponding build dependencies. Training profiles are cached observations, not guaranteed to be byte-for-byte reproducible across independent builds.

These builds require an `x86_64-linux` Nix builder with KVM available to Nix (`kvm` and `nixos-test` in its system features). Native-image compilation also needs substantial RAM. A remote Linux/KVM builder can provide these requirements.

## Pyronaut threading

`pyronaut.threading` selects between `event-loop` (the default) and `io`:

| Standard suite run | Runtime | Controller execution |
| --- | --- | --- |
| `pyronaut-async` | JVM | `async def` handlers on the Netty event loop |
| `pyronaut-io` | JVM | `def` handlers offloaded to cached platform IO threads |
| `pyronaut-native-async` | Native | `async def` handlers on the Netty event loop |
| `pyronaut-native-io` | Native | `def` handlers offloaded to cached platform IO threads |

The event-loop build converts both handlers to `async def`; the short search computation needs no `await`.
Server `thread-selection` is left at its default. Synchronous Python handlers select the IO executor before
considering that setting, and Pyronaut configures its IO and blocking executors to use platform threads.
The former Pyronaut `virtual` and `loom-carrier` modes have been removed. Historical `default` results used
synchronous handlers on IO threads; new results record the explicit threading mode so they remain distinguishable.
The async run selectors were previously named `pyronaut` and `pyronaut-native`; use the recorded `threading`
parameter to distinguish older results.

Results also record `contextPoolEnabled`, `contextPoolSize`, and `maxEventLoopContexts` in `parameters`, matching
the configured `micronaut.python.pool` properties: pooling is enabled, `size = 0` selects twice the runtime's
available processors for the shared pool, and `max-event-loop-contexts = 0` allows a dedicated Python context
for every event loop. Each context has its own GIL.

Pyronaut's SDK and benchmark app use Micronaut Core built from the source revision pinned in
`sut/pyronaut/default.nix`. Results record `micronautCoreRevision` and `micronautCoreVersion` separately
from Pyronaut's `sourceRevision`. When updating the core pin, refresh Pyronaut's dependency lock and
run all four smoke checks below.

## Python server modes

Flask, FastAPI, Emmett, and Django each have one framework module under `sut/`.
Select the HTTP server with `benchmark.python.server = "gunicorn"` or `"granian"`:

```nix
fastapi-granian = {
  imports = [ ../../sut/fastapi ];
  benchmark.python.server = "granian";
};
flask-granian = {
  imports = [ ../../sut/flask ];
  benchmark.python.server = "granian";
};
```

These are entries in `benchmark.suite.runs` in `nix/suites/standard.nix`. Run selectors can still include the
server name; they select configurations of the same framework SUT. Among these Python frameworks, the standard suite
enables `flask-gunicorn` and `fastapi-granian`, with commented entries for the other combinations. Flask and Django
default to Gunicorn; FastAPI and Emmett default to Granian. Pyronaut uses its own Netty server and threading
options, with its variants selected separately in the same suite.

| Framework module | Gunicorn mode | Granian mode |
| --- | --- | --- |
| `sut/fastapi` | ASGI worker, uvloop | ASGI, uvloop |
| `sut/flask` | WSGI, gevent worker | WSGI |
| `sut/emmett` | ASGI worker, asyncio | RSGI, asyncio |
| `sut/django` | WSGI, gevent worker | WSGI |

The shared launcher in `sut/python-server` serves HTTP/1.1 on port 8080 and HTTP/2 over TLS on port 8443,
with six workers per listener. It announces readiness only after both listeners pass their probes.
Granian gives workers 10 seconds to stop before terminating any that remain. Gunicorn ASGI uses 26.2.2
because the nixpkgs version (26.0.0) does not finish empty ASGI HTTP/2 responses correctly; WSGI retains
the nixpkgs version. All modes support py-spy profiling through the existing Python runtime configuration.

Result types identify the framework (for example `fastapi-python`); `parameters.server`, `serverVersion`,
`interface`, and worker settings identify the selected server mode. Existing saved results keep their original metadata.
FastAPI continues to use Pydantic request/response models, while all four frameworks expose `/status` and
`/search/find` (404 when no match is found).

## Smoke checks

The four Pyronaut checks exercise the status and search endpoints over HTTP/1 and HTTPS/2.
Enable the corresponding Pyronaut run entries in `nix/suites/standard.nix` before running their checks:

```sh
nix build --no-link \
  ./nix#checks.x86_64-linux.pyronaut-async-smoke \
  ./nix#checks.x86_64-linux.pyronaut-io-smoke \
  ./nix#checks.x86_64-linux.pyronaut-native-async-smoke \
  ./nix#checks.x86_64-linux.pyronaut-native-io-smoke
```

All eight Python framework/server combinations have service and profiling checks, including combinations
disabled in the suite. For example:

```sh
cd nix
nix build --no-link \
  .#checks.x86_64-linux.fastapi-gunicorn-smoke \
  .#checks.x86_64-linux.fastapi-gunicorn-profiling-smoke \
  .#checks.x86_64-linux.fastapi-granian-smoke \
  .#checks.x86_64-linux.fastapi-granian-profiling-smoke
```

Replace `fastapi` with `flask`, `emmett`, or `django` to check the corresponding framework.

The `loop` and `db` suites call nginx or PostgreSQL attachments. Their checks run a stand-in on the test VM,
set up like the OCI attachment, and send each suite document with response validation:

```sh
cd nix
nix build --no-link \
  .#checks.x86_64-linux.loop-micronaut-smoke \
  .#checks.x86_64-linux.loop-micronaut-loom-carrier-smoke \
  .#checks.x86_64-linux.db-micronaut-smoke \
  .#checks.x86_64-linux.db-micronaut-loom-carrier-smoke
```

Micronaut runs in these suites select controllers with `micronaut-framework.executeOn` (`null` for the reactive
handlers, `"blocking"` for `@ExecuteOn(BLOCKING)`) and `micronaut-framework.httpClient` (`"micronaut"` or `"jdk"`).
`/db` always runs JDBC on the blocking executor.

`micronaut-framework.eventLoopThreads` sets the size of the default event loop group, and
`micronaut-framework.loomCarrier` sets `micronaut.netty.loom-carrier.*` properties for `threading = "loom-carrier"`
runs, e.g. `{ work-spill-threshold = "4"; }`. Both are runtime settings, so such variants share one build.
`micronaut-loom-carrier-tuned-smoke` sets every loom-carrier property and checks the event loop size.

PGO checks remain available even while the production PGO entries are commented out:

```sh
cd nix
nix build --no-link \
  .#checks.x86_64-linux.micronaut-pgo-https2-6-6-smoke \
  .#checks.x86_64-linux.micronaut-pgo-https2-6-6-profiling-smoke \
  .#checks.x86_64-linux.quarkus-pgo-https2-6-6-smoke \
  .#checks.x86_64-linux.quarkus-pgo-https2-6-6-profiling-smoke
```

These checks build the complete training chain, verify the optimized server over the selected protocol, and verify runtime performance-profile artifacts. Associated non-PGO checks are `micronaut-native-smoke` and `quarkus-native-smoke` under the same `checks.x86_64-linux` namespace.
