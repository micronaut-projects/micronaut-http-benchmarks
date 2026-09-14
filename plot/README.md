# Synthetic Perf JFR

Perf symbols ending exactly in ` [AOT]` or ` [JIT]` with an owner and method
separated by the final `::` become compiled Java frames. Only owner spelling is
normalized; parameters, return types, offsets and constructor names are not
inferred. Java methods use a synthetic `()V` descriptor solely because JDK stack
rendering requires a valid descriptor. It does not describe recovered parameters
or return types. Native methods have no descriptor or class name.
Kernel identity comes only from the `[kernel.kallsyms]` DSO. Other symbols remain
non-Java `C++` frames. Direct collapsed flamegraphs keep the original symbols,
with semicolons escaped to colons.

The synthetic stack-frame schema stores `method`, `lineNumber`, `javaFrame`,
and `type`, in that order. JDK consumers need `javaFrame` to distinguish native
frames; an absent `bytecodeIndex` is reported as unknown (`-1`). Async-profiler
4.5 hard-codes four fields and reads the flag in its otherwise unavailable BCI
slot. Direct async-profiler consumers of the synthetic JFR therefore see a
positional BCI value that is not meaningful. Appending the flag as a fifth field
instead would corrupt that reader's stacks.

Before generating a heatmap, `ProfileConverter.convertHeatmap` replaces every
decoded stack location with unknown (`-1`) through the public `JfrReader` and
`JfrToHeatmap` APIs. This path is only for the single-chunk synthetic perf JFR;
ordinary async-profiler recordings retain their original locations. Generated
perf heatmaps contain no fabricated BCI or line numbers, including `@1` name
suffixes and `bci: 1` tooltips. The JFR itself retains the compatibility layout.
Frame-type constant-pool entries are registered in async-profiler order:
`JIT compiled` (1), `Inlined` (2), `Native` (3), `C++` (4), `Kernel` (5).

JDK method/class getters need a string-valued Symbol type, while async-profiler
requires its strings to use inline UTF-8 encoding, not string-pool references.
The pinned JMC 9.1.2 writer pools built-in Strings. `InlineStringType` disables
pooling for the Symbol's value field only; there is no demonstrated public-API
replacement compatible with both pinned readers. The bridge depends on JMC
9.1.2's package-private `BuiltinType` constructor and `TypesImpl` implementation,
and on classpath loading into the same runtime package, not JPMS module-path
loading. Missing symbols use null references, avoiding the unsupported compact
empty-string encoding. Consumer tests exercise both formats; dependency upgrades
must recheck this internal ABI and the heatmap location normalization.
