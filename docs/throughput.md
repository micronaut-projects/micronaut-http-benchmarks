# Throughput searches

`./bench run` measures the highest offered request rate that satisfies the configured Hyperfoil SLA. An explicit
`--rate` instead performs the existing fixed-rate measurement. `./bench suite` defaults to thorough searches.

## Protocol

Each repetition contains two fresh SUT process lifetimes on the same machines:

1. Warm up using the existing fixed-concurrency workload. Discovery begins at the lowest configured protocol rate
   (or `--start-rate`), increasing by 25% up to `--max-rate` (default 300,000). Between measurement rates, a five-second
   Hyperfoil `increasingRate` phase ramps smoothly from the preceding rate to the next. The first measurement starts
   directly after warmup. The fixed-rate discovery measurements retain their full configured duration.
2. Stop load, collect all artifacts, restore bootstrap, and redeploy. Warm up again.
3. Validate at 25%, 50%, and 75% of the last passing discovery rate, then use the fine sweep from 90% of that passing
   rate through 125% of the first failing discovery rate, rounded up and capped at `--max-rate`. This gives the fresh
   validation process 25% headroom above discovery's failure boundary in both presets. If discovery reached the ceiling
   successfully, validate through that ceiling instead.
   The three coarse steps use the normal validation duration, SLAs, and cutoff rules; they are eligible measurements.
   Rates that coincide after rounding are included only once.

Measurement rates round upward to integers and always increase; the endpoint appears exactly once. There is no descending search
or retry at a lower rate. Failure at the first discovery or validation rate is inconclusive. Warmup remains an explicit
exception to the measured sweep's overload rule: it uses fixed concurrency and retains the response checks.
Warmup defaults to 200 concurrent clients total across all agents, configurable with
`benchmark.hyperfoil.warmupUsers` independently of the measured phases' session limits.

| Preset | Warmup per stage | Discovery phase | Validation phase | Validation increase | Repetitions |
| --- | ---: | ---: | ---: | ---: | ---: |
| quick | 60s | 10s | 15s | 5% | 1 |
| thorough | 180s | 15s | 45s | 2% | 2 |

The coarse validation steps add 45 seconds in quick mode or 2 minutes 15 seconds per thorough repetition before
draining/operational overhead. Every validation stage uses this ramp before the fine sweep starting at 90%.

The discovery ramps add five seconds per transition, up to 2 minutes 10 seconds for the default 1,000–300,000 RPS
search, before draining overhead. They apply in both presets. `--discovery-ramp-duration` changes their duration;
`0s` disables them. Existing saved experiments without this setting retain their original direct rate changes.

These are configurable with `--preset`, `--warmup`, `--discovery-duration`, `--discovery-ramp-duration`, `--duration`, `--discovery-step`,
`--validation-step`, and `--repetitions`. Durations accept positive integer seconds, minutes, or hours; steps are
percentages. Thorough runs typically take 30–50 minutes per case plus infrastructure and artifact overhead.

## Validity and interpretation

Measurement uses Hyperfoil `constantRate`, with `increasingRate` for discovery transitions, `startAfterStrict`, and
`failurePolicy: CANCEL`. Ramps use the same native SLAs and response checks as the fixed-rate measurements. A passing
ramp is recorded as `RAMP_PASS` and does not advance the highest passing rate. A failing ramp is `RAMP_FAIL`: it stops
the search, excludes later phases, and uses the ramp's target as a conservative upper endpoint for fresh validation.
That endpoint is not a measured constant-rate failure. An otherwise passing ramp must complete and drain;
missing final statistics and internal generator errors remain invalid. Ramp observations remain in diagnostics,
but are omitted from the fixed-rate latency curves.
The existing native percentile
SLAs require p50 below 100ms, p95 below 200ms, and p99 below 1000ms by default for every protocol,
in both quick and thorough searches, with zero permitted request errors or invalid responses. No additional
rolling-window SLA is introduced. Native SLA definitions and results determine failure; the analysis does not
reimplement percentile comparisons.
Latency limits and error/response-validity/connection-blocking checks use separate native SLA definitions, so a
blocking or error failure cannot mask a simultaneous latency failure. Every definition must pass.

Percentile keys are fractions: `0.50` is p50, `0.99` is p99, and `0.999` is p99.9. The pinned Hyperfoil package includes
the pinned patch from [Hyperfoil PR #905](https://github.com/Hyperfoil/Hyperfoil/pull/905), converting these fractions
to HdrHistogram's percentage scale during native SLA evaluation. Earlier
builds incorrectly checked the 0.5th and 0.99th percentiles for p50 and p99 limits; their passing SLA outcomes do not
establish compliance with the intended latency limits.

Hyperfoil can start a subsequent phase before a preceding phase's final SLA evaluation arrives. This is allowed.
The daemon waits for finalized statistics, then accepts only the initial consecutive passing phases in planned order.
The first failure establishes a boundary. Every later phase is excluded, even if its own SLA passes. Missing,
unfinished, or undrained phases cannot count as passes or be skipped.
For otherwise passing phases (including warmup), response counts may exceed request counts by up to 0.1% to tolerate
minor Hyperfoil accounting inconsistencies. Missing responses, larger overcounts, empty measurements, and short
durations remain invalid; SLA and error checks are unchanged.
Each phase has a native maximum duration two minutes beyond its injection duration, bounding drain time if sessions
become stuck. This does not shorten warmup or measurement; incomplete measurements and infrastructure errors cannot establish
throughput bounds.

A finalized session-limit failure is treated exactly like an SLA failure: mark the phase `FAIL`, preserve preceding
passes, and exclude every later phase. No accompanying latency/error SLA report is required. Discovery can proceed
to fresh validation with the same 25% headroom above that failing rate, and validation can establish a bracket ending
at a session-limit failure. This is a benchmark failure boundary under the configured session budget; session exhaustion
can be a symptom of overload and queueing. Missing/undrained statistics, cancellation, and infrastructure/internal errors
still invalidate the affected measurement.

A native latency/error/response-validity SLA failure also remains `FAIL` when accompanied by connection blocking.
Queueing must not mask a reported SLA failure. Connection blocking alone, without a session-limit or other SLA/request
failure, remains `GENERATOR_LIMITED`. Missing statistics, cancellation, and internal generator errors do not enable
continuation.

`maxSessions` remains explicit: `ceil(rate × sessionLimitFactor)`, using the target rate for a discovery ramp.
Its default factor remains 2, independently of the
physical connection count, allowing HTTP/2 multiplexing. Session exhaustion fails the phase; it does not abort the
two-stage search when discovery has preceding passes. The ceiling affects Hyperfoil's preallocated session capacity;
use an appropriate ceiling for the available agent resources.

Hyperfoil reserves pools for all planned phases before starting the sweep. The cluster defaults to four 32 GiB agents;
the runner assigns each a 25 GiB Java heap (80% of VM memory, rounded down). The previous two 16 GiB agents exhausted
their 12 GiB heaps during initialization, both at the former 1,000,000 RPS ceiling and during thorough validation
with a 200,000 RPS ceiling. Capacity depends on the sum of all phases' session limits, so a dense validation sweep can
require more memory than discovery despite its lower maximum rate. Heap exhaustion is an invalid generator run,
not evidence of SUT overload. Size the rate ceiling and sweep for the available resources; the protocol never silently
lowers the requested ceiling or session factor. The increased cluster capacity still requires benchmark verification.
Discovery ramps add one phase per transition, approximately doubling its reserved session capacity at the default
rates. A short duration does not reduce that allocation. The native ramp avoids allocating a separate pool for every
intermediate rate, but its initialization cost still needs to fit the agents and controller's initialization timeout.

Only validation establishes throughput:

- `BRACKETED`: highest passing offered RPS and first failing RPS.
- `LOWER_BOUND`: the validation ceiling passed; there is no measured maximum.
- `INCONCLUSIVE`: the first measured rate failed; no lower-rate retry is attempted.
- `GENERATOR_LIMITED` or `INVALID`: the search did not establish a trustworthy upper SUT boundary. Earlier valid
  observations remain available unless the error invalidates them too. Internal Hyperfoil response-processing errors
  invalidate the affected phase; they cannot establish a SUT boundary.

Execution success is separate from the search outcome. `run.json` can say `SUCCEEDED` when the search terminated at an
expected SLA failure or produced an inconclusive result. `--wait` reports execution success; consumers must also inspect
`throughput.json` or `bench summary` before interpreting throughput.

All repetitions remain visible. A median and range are reported only when every requested repetition has a bracketed
validation result and the logical run completed successfully. With two repetitions the median is their midpoint.
Step size is search resolution, not statistical confidence or a significance test. Comparisons check workload, SLA,
protocol settings, machines, profiling, and search settings; different discovered rates are expected.

Python profiling uses `py-spy --nonblocking` at one sample per second. Blocking sampling suspends worker processes
and can introduce tail-latency spikes even at this low frequency. Nonblocking sampling avoids those pauses, at the
cost of occasional missing or partial stack samples. When investigating unstable Python latency, compare with
profiling disabled before relaxing the SLA; profiling overhead is still possible in nonblocking mode.

## Saved results and analysis

The root retains the immutable experiment link, `run.json`, `metadata.json`, `search.json`, and `throughput.json`.
Each `repetitions/N/discovery` or `repetitions/N/validation` directory retains:

- effective workload and planned phase rates/durations;
- raw Hyperfoil output, final completion evidence, and the ordered eligibility decision;
- stage lifecycle record, server/agent logs, machine and environment information;
- profiling artifacts and the original experiment closure link.

Pure Netty logs UTC timestamps, thread names, and the affected channel. Closed-channel, broken-pipe, and connection-reset
exceptions retain one full stack trace per ten-second window across all server connections; repeats are counted in a
summary emitted at the end of the window, even if the errors stop. These are counts of suppressed log events, not distinct
connections or failed requests. Other exceptions remain unthrottled. Hyperfoil request-write failures include the original
exception, channel, in-flight count, and pending-request count without dumping the complete HTTP/2 stream map.

`./bench summary RUN_DIR` includes repetitions, stage diagnostics, and any valid aggregate. `./bench compare A B` only
emits an aggregate throughput delta when settings are compatible and both aggregates are available. `./bench plot DIR`
creates a standalone report with bounds, phase diagnostics, and complete metadata. Fixed-rate analysis remains supported.
The report reuses the fixed-rate Chart.js renderer, latency formatting, color palette, machine display, and profile
conversion. It includes throughput bars and separate P50/P95/P99 curves over offered RPS, with discovery/validation controls,
per-phase hover details, and shaded configured SLA limits. Curves include eligible passes and the first SLA failure;
later phases remain in diagnostics only. Incompatible settings are plotted in separate groups, and repetitions are
shown separately. Lower bounds are labeled `≥`; invalid or inconclusive outcomes never become throughput bars.
Reports retain full-process flamegraphs and heatmaps, explicitly labeled as including warmup and excluded traffic.
Pass a stage directory to `bench plot` to render just that stage's diagnostics and profile.

Use `./bench profile RUN_DIR --stage 1/validation` to select a recording. `benchmark_measured(startTime)` includes only
passing validation intervals for adaptive runs. Discovery, warmup, the failed phase, and later phases remain available
in the complete recording but are excluded from that filter.

## Integration checks

The additional `:load-generator-oci:adaptiveHyperfoilIntegrationTest` task runs the Nix-pinned Hyperfoil against
local HTTP and HTTP/2 servers. Set `HYPERFOIL_HOME` to the built output of `nix/system/hyperfoil.nix`. It verifies
multiple active sessions on one HTTP/2 connection, response errors, first-phase failure, session exhaustion,
connection blocking, and termination of sessions stuck beyond the drain deadline.
A separate histogram-only regression verifies native percentile SLA evaluation at p50,
p95, p99, and p99.9, including passing and failing distributions. To run only that check without generating traffic:

```bash
HYPERFOIL_HOME=/path/to/built/hyperfoil ./gradlew :load-generator-oci:adaptiveHyperfoilIntegrationTest \
  --tests '*RealHyperfoilThroughputTest.nativeSlaPercentilesUseConfiguredFractions'
```
