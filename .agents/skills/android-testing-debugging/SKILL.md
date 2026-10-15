---
name: android-testing-debugging
description: "Use for Android/Kotlin testing or debugging: JVM unit/coroutine/Flow/ViewModel/codec/protocol tests, Room/Compose/instrumentation tests, regression reproducers, ADB/Logcat/dumpsys, crashes, leaks, flakiness, reproduction, source investigation."
---

# Testing, Debugging, and Source Inspection

## Risk-first test selection

Choose the cheapest deterministic test that proves the changed risk. Do not test private implementation details merely to increase coverage.

| Risk | Preferred proof |
|---|---|
| mapping/validation/calculation | pure unit test |
| ViewModel state/effects | fake repository + coroutine test |
| cancellation/race/debounce/retry | `runTest`, virtual time, controlled Flow |
| repository strategy/cache | fake data sources/integration test |
| DAO/query/transaction | in-memory or real test DB |
| schema migration | Room migration test with exported schema/assets |
| WorkManager | worker test driver/fake dependency |
| Compose behavior | semantics/UI test |
| visual parity/regression | screenshot test with existing framework |
| deep link/permission/system API | host/instrumentation test |
| R8/reflection/serialization | minified release-like smoke test |
| startup/jank | Macrobenchmark/release measurement |

TDD is useful when behavior can be expressed before implementation. Do not force ceremonial red-green-refactor for trivial mechanical edits, generated code, or bugs whose root cause is not yet understood.

## Mandatory behavior test matrix

Scale by the AGENTS.md §17 tier: Risky changes classify all ten rows; Normal changes cover rows 1 and 10 plus any row the change obviously touches; Tiny changes are exempt. For every Risky new or changed production behavior, logic path, or bug fix, classify all ten rows before implementation and close them before completion. This is a coverage contract, not a demand for ten separate tests: one deterministic test may prove multiple rows when its boundary and oracle genuinely cover them. Use the smallest stable boundary that contains the failure mechanism. Do not add fake layers or broad end-to-end infrastructure just to satisfy the matrix.

Status each row as:

- `COVERED`: automated test exists and its result was inspected;
- `N/A`: the category is structurally absent from this change, with a concrete reason;
- `UNVERIFIED`: a required test exists or is clearly identified but cannot be executed because the needed device/platform/infrastructure is unavailable. This is residual risk, never equivalent to passing.

Lack of time, inconvenience, or an expensive suite is not a valid `N/A`. Run focused tests rather than the entire suite when that gives the same signal. Pure documentation/copy/build-metadata changes with no runtime behavior are outside this matrix.

| # | Required category | Minimum proof |
|---|---|---|
| 1 | Core logic | Exercise actual rules plus boundary values, invalid inputs, conflicting conditions, and expected outputs. Prefer pure/unit tests when the rule is platform-free. |
| 2 | State transitions | Exercise the meaningful before/after transitions, including inverse transitions and permission/expiry boundaries where they exist. Assert both final truth and forbidden stale state. |
| 3 | One happy-path end to end | Drive the real feature path across every production layer that exists: UI/trigger -> ViewModel/owner -> repository/service -> persistence/platform -> business result -> rendered/applied observation. Do not invent a layer solely for the test. |
| 4 | Persistence + process death | Persist representative configuration/state using the real durable boundary, discard process-owned/in-memory state, reconstruct the feature, and verify restoration from authoritative truth. ViewModel recreation alone is not process-death proof. |
| 5 | Failure + recovery | Make the most important dependency fail or disappear, verify safe failure/no fake success or unsafe partial state, restore it, then verify convergence/retry/manual recovery according to the contract. |
| 6 | Cross-feature interaction | Identify the few systems that can mutate, override, gate, or race the same truth. Test those collision points, such as live cast + Media3 playback commands, manual + automatic download of one ID, or role switch + running workers/listener. Do not build a Cartesian-product matrix. |
| 7 | Concurrency / duplicate execution | Double-trigger, rapidly toggle, overlap refreshes/work, or deliver stale completion. Verify the declared latest-wins/first-wins/single-flight/serialized/idempotent semantics, with no duplicate write, deadlock, leak, or corrupt state. |
| 8 | UI behavior | Verify critical controls reflect backend truth, validation blocks invalid action, loading/error states are honest, navigation/recreation preserves required state, and success is not shown before the owning mutation commits. |
| 9 | Lifecycle / reboot | For foreground services (Media3 playback, LAN listener, MediaProjection capture), START_STICKY restarts, alarms, receivers, workers, permissions, or similar system-owned behavior, exercise relevant restart/reboot, background/foreground, recreation, and permission-loss paths. Ordinary CRUD may mark this `N/A` only when no extra system/lifecycle behavior exists beyond row 4. |
| 10 | Regression | Every fixed behavior bug gets a permanent test that reproduces the exact failure mechanism or state sequence and fails if the bug returns. If the original failure depends on unavailable OEM/hardware behavior, keep the narrowest deterministic reproducer plus the explicit device gap. |

Before coding a meaningful behavior change, write a compact matrix such as:

```text
Core logic: test/class or planned oracle
Transitions: ...
Happy path E2E: ...
Process death: ...
Failure/recovery: ...
Cross-feature: ...
Concurrency: ...
UI: ...
Lifecycle/reboot: ...
Regression: existing bug reproducer / N/A for new behavior
```

Keep the suite healthy:

- Prefer several narrow tests over one giant brittle scenario when failures need distinct oracles.
- Reuse fixtures/fakes only when they preserve the boundary semantics under test.
- Do not duplicate the same assertion at five layers merely to satisfy row count.
- For process/lifecycle/platform proof, use instrumentation or a faithful framework boundary when JVM fakes cannot reproduce the behavior.
- For every row, assert an observable invariant, not merely that a method was called.
- For serious bugs, create/preserve the failing reproducer before or alongside the fix whenever practical.

## Test boundaries

- Test the owner’s contract, not every delegation.
- Prefer hand-written fakes for owned interfaces and stateful behavior.
- Use mocks when interaction order/count is the actual contract or a framework boundary is hard to fake.
- Follow existing assertion/mocking/test framework; do not add one for a single test.
- Avoid real network, wall clock, randomness, filesystem, and delays unless the test is explicitly integration/end-to-end.
- Tests must not depend on execution order or shared mutable singleton state.
- Restore global/static/system settings changed by a test.

## Coroutine and Flow tests

- Use `runTest`, not `runBlocking`.
- Share the test scheduler across injected test dispatchers.
- Set/reset Main only if code genuinely uses Main and existing rule supports it.
- Advance virtual time intentionally (`runCurrent`, `advanceTimeBy`, `advanceUntilIdle`).
- Never use real `Thread.sleep`/`delay` as synchronization.
- Test cancellation, late/stale results, duplicate operations, retry bounds, and child failure semantics.
- Collect hot flows before triggering when the emission has no replay and that loss policy is under test.
- Cancel background collectors at test end.
- Use Turbine only if existing/justified; ordinary collection/fakes are acceptable.

## ViewModel tests

Verify externally observable behavior:

- initial/restored state;
- action -> loading/content/error transitions;
- stale content preserved during refresh failure;
- validation;
- duplicate taps and operation policy;
- transient effect count/order/loss semantics;
- cancellation on clear/new query;
- SavedStateHandle argument validation;
- critical outcome represented in durable state rather than ephemeral event.

Avoid verifying exact internal repository call order unless it is part of the contract.

## Repository/data tests

- Use fakes for strategy tests and DB/API test doubles for boundary integration.
- Test cache key/TTL/invalidation/account switch.
- Test local/remote merge, outbox, conflicts, idempotency, partial failure, retry.
- Room tests cover query ordering, relations, indexes when performance matters, transaction atomicity, and migrations.
- Do not use destructive migration or reset DB in migration tests to make them pass.
- DataStore tests use isolated temp files/scopes and cover migration/default/corruption policy.
- Paging tests cover keys, invalidation, load states, query changes, RemoteMediator transactions.

## Compose/UI tests

Test user-observable semantics and behavior:

- screen state rendering;
- click/input/scroll/focus;
- loading/empty/error/offline/disabled;
- accessibility role/label/state/actions;
- navigation callback/effect at host edge;
- large font/RTL/window size where important.

Prefer semantic matchers. Add `testTag` when stable semantics/text cannot uniquely identify the node; do not turn production UI into tag-only test implementation.

Avoid brittle selectors based on node index, internal layout hierarchy, or localized text when semantics/IDs are available.

Screenshot tests:

- use existing framework and deterministic device/font/density/theme/clock/data;
- disable/control animations;
- cover meaningful states/window sizes;
- inspect diffs; never update golden merely to make CI green;
- capture baseline before migration/refactor.

## Instrumentation and system tests

Use when framework behavior cannot be proven on JVM:

- permissions/results/intents/deep links;
- WebView/ContentProvider/FileProvider;
- notification/PendingIntent;
- real DB encryption/storage;
- Camera/Media/hardware integration;
- Hilt/DI graph;
- WorkManager system integration;
- accessibility services/input devices.

Keep emulator/device assumptions explicit. Use managed devices/test orchestrator/sharding only if repository already supports or the suite needs them.

## Debugging protocol

1. Reproduce reliably or define evidence gap.
2. Capture exact variant, device/API, account/state, timing, steps, and recent changes.
3. Read the complete error/stack trace, including deepest relevant `Caused by`.
4. Identify the owning component and first bad state/event.
5. Form a falsifiable hypothesis.
6. Add a focused log/test/trace to confirm.
7. Fix the root cause at the owner.
8. Add regression proof.
9. Remove temporary sensitive/noisy instrumentation.

Do not shotgun-edit, catch/suppress the crash, add delays, or reset state before understanding the failure.

## Logcat and crashes

- Filter by package/PID/tag but retain enough surrounding logs.
- Capture full stack, thread, process, build/version, and preceding lifecycle/network/data events.
- Root cause may be lower in cause chain; do not fix only top wrapper.
- Redact tokens, PII, payloads, URLs, and identifiers before sharing.
- For native crashes, collect tombstone/symbols/build IDs as available.
- For release obfuscation, use the exact shipped mapping and symbols.
- Archive mapping files for releases.
- Do not infer release behavior solely from debug.

## ANR

ANR investigation focuses on main-thread blockage/contention:

- collect ANR traces, main thread stack, binder/lock holders, CPU/I/O state;
- identify synchronous disk/network/DB/crypto/image decode, long layout/draw, lock inversion, binder call, broadcast/service timeout;
- inspect all involved threads, not only main;
- distinguish app-not-responding from slow rendering;
- fix ownership/threading/algorithm, not by increasing timeout.

## Memory leaks and OOM

- identify retained object path and expected lifecycle;
- inspect Fragment view binding, Activity/Context in singleton, listener/receiver/callback cleanup, coroutine scope, Compose/View interop, bitmap/image size, cache bound, Media/Camera resources;
- OOM can be excessive live data or burst allocation, not a classic leak;
- heap dump may contain sensitive data—handle accordingly;
- do not add LeakCanary to production or dependencies without repository/user intent.

## Compose debugging

Use layout inspector/recomposition counts/compiler reports/semantics tree as appropriate.

Check:

- unstable inputs or false stability annotations;
- state mutation in composition;
- wrong effect keys/stale callbacks;
- duplicate collectors/work on re-subscription;
- missing list keys/key collisions;
- expensive item/composition work;
- nested infinite constraints;
- insets/focus/semantics;
- View resource disposal.

A screenshot shows appearance; a semantics/layout tree shows structure/state. Use both for the relevant question.

## Gradle/build failures

- start with the first meaningful failure, not cascading errors;
- run with stacktrace/info only as needed;
- inspect dependency graph, plugin management, repositories, version catalog, toolchain compatibility, generated source/KSP output;
- reproduce with narrow task/variant;
- do not run `clean` by default—it destroys useful incremental evidence and is rarely a root-cause fix;
- avoid deleting caches as a first response;
- do not disable configuration cache/lint/tests globally without proof and a scoped plan.

Build scans may upload metadata/source paths; obtain authorization and inspect privacy policy before `--scan`. Prefer local `--profile` when upload is not approved.

## Android/AndroidX source verification

Use source when public docs are ambiguous or behavior/version details matter.

Priority:

1. source checked into repository or Gradle source artifact;
2. installed SDK/AOSP/AndroidX source or official source browser;
3. official release notes/API docs/samples;
4. third-party explanation only as secondary evidence.

Match the exact dependency/API level/commit where possible. Separate implementation detail from public contract; do not rely on an internal detail that can change unless version-pinned and tested.

Search strategy:

- locate symbol declaration;
- inspect call path, annotations, threading/lifecycle, default parameters;
- inspect tests/samples and change/release notes;
- verify platform guards and experimental opt-ins;
- cite/record exact version in investigation notes when it affects fix.

Do not copy large internal implementations when a stable public API solves the problem.

## Device/ADB safety

Allowed non-destructive diagnostics when environment permits: list devices, logcat, dumpsys, layout/semantics dump, screenshot, install debug build, launch test activity, pull authorized trace.

Require explicit approval before:

- clearing app data;
- uninstalling production app;
- modifying real account/data;
- factory reset/emulator wipe;
- granting/revoking sensitive permissions outside test setup;
- uploading traces/build scans/logs;
- running destructive shell commands.

## Coverage and quality gates

Coverage is evidence, not goal. Prioritize meaningful branches/invariants. Do not add tests that only assert mock calls or getters. Respect existing lint/Detekt/Ktlint/code coverage setup. Narrow suppression includes rationale and smallest scope.

- Keep JaCoCo/Kover or equivalent reports variant- and module-aware; merge only compatible execution data.
- Exclude generated code only through the repository's explicit policy. Do not exclude difficult production packages or add no-op tests to satisfy a threshold.
- Treat line/branch thresholds as regression gates, not proof of correctness. Review untested mutation, error, cancellation, migration, and security paths.
- Lint/static analysis baselines must not become dumping grounds. New findings in changed code require a fix or a narrow documented suppression.
- Screenshot systems such as Paparazzi, Roborazzi, Compose Preview screenshot testing, or device screenshots are interchangeable only at the strategy level; use the framework and Gradle/AGP version the repository supports.
- Archive human-readable reports/artifacts in CI when they are needed to diagnose a failed gate, while avoiding sensitive test data.

## Completion report

State:

```text
Root cause/evidence:
Changed behavior:
Tests/checks run and result:
Checks not run and why:
Remaining risk/assumption:
```

## Anti-patterns

- fix before reproduction/evidence;
- suppress exception/log and call it solved;
- `runBlocking` or real sleeps in coroutine tests;
- mock every class and verify implementation trivia;
- no migration test for schema change;
- UI test selectors by index/hierarchy;
- golden update without inspecting diff;
- debug-only verification for R8/release issue;
- `clean`/delete caches as default diagnosis;
- private logs/traces uploaded without consent;
- app data cleared to hide restoration/data bug;
- source implementation assumed from training memory instead of matching version.


## Deep implementation protocol

### Build the test from the risk

Start with the defect or invariant, not the class under test. Define stimulus, observable output, forbidden output, timing/lifecycle context, and independent oracle. Select the lowest stable boundary that includes the failure mechanism.

A strong test normally contains:

```text
Given: state/data/time/dependency behavior that makes the bug possible
When: one user/system action or controlled sequence occurs
Then: externally meaningful state/data/effect is observed
And: forbidden duplicate/stale/leaked/unsafe behavior is absent
```

### Determinism

Control dispatchers/scheduler, clock, randomness, IDs, network server, database, filesystem, and account only where the behavior depends on them. Avoid sleeps, real network, wall clock, order-dependent global state, and assertions that depend on incidental implementation timing.

### Debugging evidence ladder

1. reproduce and preserve exact environment;
2. reduce to the smallest failing path;
3. identify owner/state/thread and last known correct boundary;
4. form a falsifiable hypothesis;
5. collect stack/trace/log/state/source evidence;
6. patch one cause;
7. rerun reproducer and neighboring regression cases;
8. remove temporary diagnostics and inspect final diff.

## AI-generated code hazards

- tests mirror implementation or assert mock calls while user-visible result remains wrong.
- generated test passes before the production fix or cannot fail when guard is removed.
- over-mocking Room/serialization/WorkManager/navigation/framework boundary that contains the defect.
- `delay`/sleep used instead of virtual time or synchronization.
- `runBlocking` and uncontrolled Main dispatcher in unit tests.
- swallowing exceptions in test helpers, retries, or `runCatching` that turns failure into pass.
- broad snapshot/golden update accepted without inspecting intended versus unintended changes.
- flaky test “fixed” by increasing timeout/retry or disabling it.
- debugging by random edits, `clean`, cache deletion, or dependency upgrade without evidence.
- only line coverage reported; failure paths, assertions, and oracle quality ignored.
- private implementation exposed solely for generated tests.
- source/documentation assumptions used despite effective runtime/version differing.

## Post-change audit

### Test quality audit

- Demonstrate that the new/updated test would fail for the prior defect or under fault injection.
- Assert both intended result and absence of critical forbidden behavior.
- Run the test repeatedly when race/flakiness is the risk.
- Run neighboring tests and the smallest integration boundary that could contradict the fake.
- Verify test isolation: order, locale, time zone, account, database, dispatcher, and shared singleton state.
- Inspect skipped/ignored/quarantined tests, warnings, and stderr—not only exit code.
- For UI tests, assert semantics/user behavior and use screenshots as secondary evidence.
- For migrations/release/R8/platform integrations, test the real artifact/variant.

### Root-cause closure

A bug fix is not closed until the original reproducer no longer fails, the causal explanation matches evidence, and the patch does not merely mask symptoms. Record any unproven hypothesis as residual risk.

### Evidence record

```text
Risk/invariant tested:
Why selected boundary includes the defect:
Independent oracle:
Prior-failure/fault-injection proof:
Determinism controls:
Repetition/flakiness result:
Integration/release evidence:
Root-cause confidence and residual risk:
```
