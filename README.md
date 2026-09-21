# Micronaut HTTP server benchmarks

Benchmark suites, workloads, SUT builds, and deployable NixOS systems are defined in `nix/`. The OCI load generator consumes the `benchmark-metadata` and `benchmark-definitions` packages from that flake.

## Building systems

From `nix/`, build a normal run with:

```sh
nix build .#standard-micronaut-system
```

Suite definitions in `nix/suites/` select framework configurations, documents, and protocols. The load generator builds and publishes the selected system outputs through the ordinary Nix cache before activating them on benchmark infrastructure.

## Profile-guided optimization

The `micronaut-pgo` and `quarkus-pgo` entries in `nix/suites/standard.nix` are opt-in. Uncomment the desired entry, then build its document/protocol-specific system, for example:

```sh
cd nix
nix build .#standard-micronaut-pgo-https2-6-6-system
```

This single build creates an instrumented binary, starts it in a NixOS test VM, trains it, and builds the optimized binary using the resulting `default.iprof`. Each enabled document/protocol pair gets its own optimized system named `${suite}-${run}-${protocol}-${document}-system`. Instrumented binaries are shared between cases of the same framework configuration.

Training uses the suite's request and response definitions and the selected protocol, including TLS. One concurrent user sends requests for two minutes, with one connection and no pipelining. Response validation and request timeouts apply, but throughput and latency targets do not. The service stops gracefully to flush its profile; training failures or a missing profile fail the build.

Profiles and optimized binaries are normal Nix build outputs, reused across benchmark repetitions and locations. No benchmark infrastructure, downloaded profile directory, or separate preparation command is needed. Source, toolchain, training workload, or relevant configuration changes invalidate the corresponding build dependencies. Training profiles are cached observations, not guaranteed to be byte-for-byte reproducible across independent builds.

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
