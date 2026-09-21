# Micronaut HTTP server benchmarks

Benchmark suites, workloads, SUT builds, and deployable NixOS systems are defined in `nix/`. The OCI load generator consumes the `benchmark-metadata` and `benchmark-definitions` packages from that flake.

## Building systems

From `nix/`, build a normal run with:

```sh
nix build .#standard-micronaut-system
```

Suite definitions in `nix/suites/` select framework configurations, documents, and protocols. The load generator builds and publishes the selected system outputs through the ordinary Nix cache before activating them on benchmark infrastructure.

## Running the OCI suite

Configure `suite.name` and one `[suite.location]` table in `load-generator-oci/src/main/resources/application.toml`. Each framework/load combination runs once, in shuffled order, on one reused infrastructure. The SUT is restarted for each case and the bootstrap configuration is restored between cases.

Results are written to `output/<run>-<load>/`, with infrastructure logs in `output/infra/`. The index contains no repetition field; `output/index.new.json` is promoted to `output/index.json` only after the suite and cleanup succeed. Failures during execution stop the suite and retain the staging index and final progress snapshot for diagnosis.

For independent measurements, invoke the suite again and archive `output` between invocations. The runner clears the selected compartment before and after the suite, so do not run concurrent suites in that compartment. Resources in previously configured locations must be cleaned separately.

The former `suite.repetitions`, `suite.max-concurrent-runs`, and `suite.infrastructure-mode` options and `[[suite.location]]` array are removed. Output names no longer have repetition suffixes such as `-0` or `infra-0`; update external scripts accordingly. Historical output compatibility is not maintained.

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
