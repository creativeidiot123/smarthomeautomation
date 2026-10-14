---
name: android-performance-release
description: "Use for measured Android performance/release issues: startup, jank, Compose, memory, battery, Perfetto/benchmarks, R8/ProGuard, APK size, minified-only failures. Measure first."
---

# Performance, Profiling, Perfetto, and R8

## Measure before optimizing

Define:

```text
User-visible symptom:
Metric and target:
Device/API/build variant:
Scenario and input data:
Baseline samples:
Trace/profile evidence:
Suspected owner:
Regression test/benchmark:
```

Use release/profileable/minified conditions representative of production. Debug builds, debugger attachment, emulator load, and logging can distort results.

Change one variable at a time and remeasure. Do not optimize based solely on intuition or generic checklists.

## Performance domains

- startup: time to initial display/full usability, initialization critical path;
- frames/jank: main/UI/render thread work, layout/draw, GPU, input latency;
- Compose: recomposition/layout/draw and allocation;
- memory: retained heap, peak/burst allocation, bitmap/cache size, GC;
- CPU: hot methods, algorithm, serialization, crypto, image processing;
- I/O: DB/file/network waits and fsync;
- IPC/binder: system service/content provider contention;
- battery/power: wakeups, location/sensors/radio, jobs, foreground work;
- build size/runtime: R8/resource shrinking, startup class layout;
- build performance: handled primarily in Gradle skill.

## Startup

Inspect Application/ContentProvider initializers, DI graph creation, eager singletons, SDK initialization, disk/network reads, class loading, and first screen work.

- defer non-critical initialization;
- use App Startup/provider ordering only with clear ownership;
- avoid custom ContentProvider for app initialization unless necessary;
- initialize SDKs lazily or after consent where appropriate;
- do not move required work later if it causes first-use freeze;
- keep first frame and fully drawn metrics distinct;
- report fully drawn at the real product-ready point;
- baseline profiles/startup profiles require representative journeys and release verification.

## Jank and rendering

Investigate frame timeline/Perfetto rather than adding random `remember`.

Check:

- main-thread disk/network/DB/blocking locks;
- heavy composition/item transforms;
- repeated measure/layout and intrinsic sizing;
- bitmap decode/upload and large images;
- overdraw/expensive blur/shadow/clipping;
- animation allocation or concurrent animations;
- RecyclerView/Lazy list identity and reuse;
- GC/large allocation bursts;
- binder/system service calls;
- input handling and nested scroll.

Fix the dominant slice and verify frame metrics. Do not hide jank by reducing functionality or disabling animation globally without product approval.

## Compose performance

- identify which state invalidates which subtree;
- inspect stability/skipping reports when relevant;
- narrow state reads;
- remove state writes from composition;
- move expensive transforms out of hot scopes;
- use stable keys/content types;
- cache expensive draw/layout/request objects with correct keys;
- avoid false `@Stable/@Immutable` promises;
- avoid unnecessary `derivedStateOf`, `remember`, or wrapper composables;
- test behavior before and after; lower recomposition count is not automatically faster if work moves elsewhere.

## Memory

Differentiate:

- leak: object retained beyond expected lifecycle;
- unbounded cache/collection;
- oversized live model/bitmap;
- allocation churn/GC;
- native/GPU memory;
- fragmentation;
- resource not closed.

Check Activity/Fragment/View binding, Context in singleton, listeners, callbacks, coroutine scopes, WorkManager input, image size, Media/Camera, WebView, Compose interop, Room query size, paging/cache bounds.

Fix ownership and bounds. Do not call `System.gc()` or clear all caches as a production solution.

## StrictMode and diagnostics

Use StrictMode in debug/internal builds to expose disk/network on main, leaked closeables, unsafe intents/cleartext where supported. Keep penalties appropriate; do not crash production users. Redact and rate-limit any uploaded diagnostic signal.

## Macrobenchmark and baseline profiles

- benchmark a release-like, profileable target module;
- control compilation mode, startup mode, device state, iterations, and setup;
- use stable user journeys and deterministic seed data;
- separate cold/warm/hot startup;
- collect frame timing for real interactions;
- generate baseline profile from representative critical journeys;
- verify profile packaged/installed and improves target metrics;
- do not write overly broad artificial journeys merely for coverage;
- keep benchmarks outside normal app APK according to project convention.

## Perfetto trace analysis

When a trace is provided:

1. record trace source/config/duration/device/build;
2. define time window and process/thread identifiers;
3. inspect overview and domain tracks;
4. correlate symptom with slices/frame timeline/scheduler/memory/I/O/IPC;
5. form and test hypotheses with SQL;
6. report evidence timestamps/durations and owner;
7. propose the smallest fix and measurement to confirm.

Do not infer causality from one coincident slice; compare scheduling state, dependencies, and repeated occurrences.

### Perfetto SQL safety

- join by `upid`/`utid`, not recycled `pid`/`tid`, when unique trace identity matters;
- account for open-ended `dur = -1` using trace end when calculating ends/sums;
- prefer standard-library interval modules/SPAN_JOIN over fragile timestamp equality;
- SPAN_JOIN inputs must not contain overlapping intervals within a partition; partition correctly;
- materialize required intermediate interval tables when SPAN_JOIN requires it;
- use exact module/schema names from the matching Perfetto stdlib/version;
- qualify columns with aliases;
- extract args with supported argument functions rather than string parsing;
- make reusable query objects idempotent (`CREATE OR REPLACE` where supported; drop virtual tables/indexes when needed);
- use standalone self-contained queries because trace processor state may not persist across runs;
- prefer `GLOB` only when Perfetto guidance/version supports the intended matching; do not globally ban SQL `LIKE` without measuring/understanding semantics;
- execute and validate queries; do not simplify away requested overlap/math to make SQL pass.

Keep temporary trace SQL outside product source unless it is an intentional diagnostic asset. Traces may contain private data.

## R8 and shrinking

Inspect:

- release `minifyEnabled`/resource shrinking/full mode;
- app and consumer ProGuard rules;
- reflection/serialization/JNI/WebView/DI generated code;
- missing rules and library versions;
- mapping/usage/seeds/configuration outputs;
- baseline startup behavior and crash reports.

Rules:

- prefer library consumer rules for library-owned reflection;
- keep only members/classes actually reached reflectively/JNI/serialization;
- use annotation/implements/member-specific rules instead of package-wide `-keep`;
- preserve attributes only when required;
- do not suppress warnings broadly (`-dontwarn **`);
- test a minified release build and affected flows;
- archive mapping/symbol files per shipped version;
- use retrace with exact mapping;
- compare APK/AAB size and kept-reason evidence before removing a rule.

Do not modify R8 rules from a static heuristic alone when quantitative kept-reason/config analyzer output is available. Conversely, do not require a bleeding-edge analyzer unavailable in the project; use compatible evidence.

## App size

- inspect resource/code contribution with approved analyzer;
- remove unused assets/dependencies/features at owner;
- use density/vector/webp/format decisions based on quality and platform support;
- avoid duplicate native ABIs/resources;
- dynamic features only when product/install benefit justifies complexity;
- shrinking rules must not trade runtime correctness for size.

## Battery and background

- batch/defer work appropriately;
- use WorkManager constraints/unique work;
- avoid frequent polling/wakeups;
- stop location/sensors/camera/media at lifecycle boundary;
- use push/event-driven updates where viable;
- consider radio tail and payload size;
- foreground service only for user-visible policy-compliant ongoing work;
- test Doze/app standby/background restrictions.

## Reporting

Include:

```text
Scenario/build/device:
Baseline metric:
Evidence (trace/profile/kept reason):
Root cause:
Change:
After metric and sample count:
Trade-offs/regression checks:
```

Do not report a performance “win” without before/after comparable measurement.

## Anti-patterns

- optimizing debug/emulator behavior as production fact;
- premature broad rewrite;
- random `remember`/`@Stable` annotations;
- `System.gc()`/cache wipe as fix;
- disabling animation/lint/shrinking to hide issue;
- broad package `-keep` or `-dontwarn`;
- deleting keep rules without minified test;
- mapping file not archived;
- Perfetto joins on recycled PID/TID or ignoring `dur=-1`;
- build scan/trace uploaded without consent;
- benchmark with uncontrolled data/device/compilation;
- baseline profile generated from non-representative journey;
- claiming improvement from a single noisy run.


## Deep implementation protocol

### Define a performance hypothesis

Record metric, user scenario, device/API/variant, thermal/power/network state, baseline distribution, target/regression budget, suspected cause, and trace/profile evidence. Change one causal variable at a time where practical.

Debug builds, first-run compilation, emulator-only measurements, and a single trace are diagnostic clues, not release performance proof. Use release-like builds and repeated measurements appropriate to variance.

### Optimization ownership

Prefer eliminating unnecessary work over micro-optimizing syntax. Identify who schedules, repeats, caches, allocates, draws, decodes, queries, or keeps the resource alive. Ensure an optimization does not create stale data, larger permanent memory, lifecycle leaks, or correctness changes.

### R8 proof

Keep rules must correspond to a concrete reflection/JNI/serialization/service-loader contract. Prefer library consumer rules and targeted attributes/members. Verify behavior in the minified shipping variant and preserve mapping files.

## AI-generated code hazards

- declaring a bottleneck from code appearance without measurement.
- optimizing debug Compose recomposition counts while release user metrics are unknown.
- adding `remember`, caches, pools, `@Stable/@Immutable`, or derived state without proving semantic validity and invalidation.
- moving work to background but leaving duplicate work or main-thread result processing.
- parallelizing I/O and causing server/DB contention or changed ordering.
- broad R8 keep rules, disabling optimization, or keeping entire packages to stop one crash.
- baseline profile generated from non-representative flows or stale package/version.
- benchmark affected by setup, logging, network variance, thermal throttling, or emulator noise.
- memory “fix” that merely increases cache or heap lifetime.
- trace SQL copied blindly, using wrong process/thread/time window or expensive unbounded queries.
- claiming improvement from one run or incomparable baselines.

## Post-change audit

### Measurement proof

- Re-run the identical scenario and measurement setup before/after with enough iterations.
- Report distribution or at least repeated values, not only the best run.
- Confirm functional output is unchanged under the optimized path.
- Inspect trace for shifted work; reduced main time that becomes extra battery/network/background work is not automatically a win.
- For memory, verify retained-object owner and post-GC steady state, not only allocation count.
- For startup, distinguish cold/warm/hot and include process/compilation state.
- For Compose, correlate recomposition/skips with frame timing and expensive work.

### Release/R8 checks

- Build and run minified release-like variant.
- Retrace any crash with the exact mapping.
- Exercise reflection, serialization, navigation, database, SDK initialization, and JNI paths affected by rules.
- Inspect `usage`, `seeds`, APK/AAB size, and rule scope where available.

### Evidence record

```text
Metric/scenario/environment:
Baseline distribution:
Hypothesis and code owner:
After distribution:
Correctness regression checks:
Trace/profile evidence:
Minified/R8 evidence:
Remaining uncertainty/noise:
```
