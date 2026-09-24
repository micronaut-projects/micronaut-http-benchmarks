# Micronaut HTTP server benchmarks

Suites, workloads, and deployable NixOS systems are defined in `nix/`. The Micronaut CLI resolves experiment
derivations; the Micronaut daemon executes them on warm OCI infrastructure.

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
warmups and `--duration` overrides validation duration. The default ceiling is 1,000,000 RPS.
Hyperfoil preallocates sessions for every phase; this ceiling exhausted the configured 16 GiB agents during verification.
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
Adaptive outputs also contain versioned `search.json`; their YAML is a warmup plus one measurement-phase template.
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

## Smoke checks

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
