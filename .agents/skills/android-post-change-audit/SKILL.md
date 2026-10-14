---
name: android-post-change-audit
description: "Use after any Android/Kotlin implementation or config edit to audit production wiring, architecture, logic, races, lifecycle/leaks, security, release regressions, tests, and overengineering."
---

# Android App Audit

Run this skill as the independent verification pass after every implemented feature, fix, refactor, migration, schema/configuration change, or dependency/build change. Audit the changed risk before declaring completion.

**Scale by the AGENTS.md §17 tier.** Risky: run every applicable section and fill the full mandatory report below. Normal: run sections 1 (scope), 2 (wiring), the no-overengineering gate, and 12 (tests) at the size of the change, and report in the short AGENTS.md §17 format. Tiny: diff check only. The templates are aids; never pad them with `N/A` rows to look complete.

The audit is **not** permission to redesign the app. Its job is to prove that the requested behavior is actually wired, stable, and architecture-preserving. Prefer a small correction over a new layer, framework, abstraction, cache, or broad cleanup.

## Audit contract

Before auditing, establish:

```text
Requested outcome:
Observable acceptance condition:
Baseline or original failure:
Changed files/modules/contracts:
Canonical authority and observable truth:
Trigger-to-commit path:
Truth-to-render path:
Lifecycle owner:
Concurrency/duplicate policy:
Highest-risk failure modes:
Checks available in this repository:
```

Rules:

- Audit the **actual executable path**, not the intended architecture described by names or comments.
- Trace both directions: user/system trigger to authoritative effect, and authoritative truth back to the rendered/observable result.
- Verify new code is reachable, its dependencies are bound, and obsolete paths cannot still execute.
- Distinguish a confirmed defect, a credible risk, and a style preference. Do not report them as equivalent.
- Do not invent findings to make a report look comprehensive.
- Do not claim success from compilation, a mocked happy path, or visually plausible code.
- Do not expand a focused change into a repository-wide redesign unless the changed contract genuinely crosses those boundaries.
- When a defect introduced by the change is found, apply the smallest architecture-compatible fix and rerun the affected audit checks. Stop and report when correction requires product decisions, destructive action, unavailable infrastructure, or material scope expansion.

## Audit modes

Choose the narrowest mode that covers the risk:

| Mode | Use for | Default scope |
|---|---|---|
| Focused post-change audit | every implementation | diff, owners, direct callers/callees, affected tests/contracts |
| End-to-end flow audit | broken or new user/system flow | trigger through commit and truth back to UI/consumer |
| Architecture audit | ownership/dependency concern | affected feature/module boundaries, not unrelated modules |
| Concurrency/lifecycle audit | stale data, duplication, leak, cancellation, worker issue | operation owner, collectors, callbacks, scopes, retry paths |
| Data-integrity audit | Room/cache/sync/migration/account issue | schema, transaction, source strategy, reconciliation, restoration |
| Release-boundary audit | DI/R8/manifest/serialization/variant/platform change | affected variants, generated artifacts, merged manifest, minified path |
| Incident audit | crash/ANR/data/security regression | evidence chain and root cause before patching |

A broad repository audit is expensive and noisy. Run it only when explicitly requested or when a shared contract change makes focused scoping unsafe.

## 1. Scope and baseline gate

- Read the final diff and list all changed runtime, test, build, manifest, schema, resource, generated, and configuration surfaces.
- Include indirect effects: changed interface implementations, DI bindings, serializers, navigation destinations, workers, Room entities/DAOs, resource names, ProGuard/R8 rules, feature flags, and build variants.
- Establish the original failure or acceptance observation whenever practical.
- Identify protected behavior that must remain unchanged.
- Separate pre-existing issues from regressions caused or exposed by the change.
- Search for duplicate implementations, legacy branches, temporary adapters, TODOs, dead flags, and old call sites left behind.
- Confirm no unrelated formatting, generated output, dependency update, or cleanup obscures the behavioral diff.

## 2. Wiring and end-to-end pipeline

Trace the applicable pipeline explicitly:

```text
Trigger/input
  -> UI/receiver/worker/system callback
  -> action or command owner
  -> state/business decision
  -> repository/service boundary
  -> DAO/API/DataStore/file/platform operation
  -> authoritative commit or acknowledged side effect
  -> observable truth/source notification
  -> repository mapping
  -> ViewModel/presenter state transition
  -> lifecycle-aware collector
  -> rendered UI / emitted external result
```

Audit each link:

- The trigger is registered, reachable, enabled in the relevant variant, and not shadowed by an older handler.
- Parameters, IDs, account/tenant context, nullability, units, locale/time interpretation, and enum/serialization values survive each boundary correctly.
- DI has exactly the intended binding, qualifier, and scope; no manual construction bypasses it.
- Navigation routes, deep links, Activity Result contracts, permissions, notification actions, intents, workers, receivers, providers, and manifest entries point to the changed implementation.
- API methods, DTO fields, mappers, DAO queries, entity columns, DataStore keys, and repository methods line up semantically—not merely by type.
- The mutation reaches a real commit point. “Request launched” is not success.
- The committed result is observable by the actual consumer. A write with no invalidation/emission/refresh path is incomplete wiring.
- Errors and cancellation travel to the correct owner without being converted to fake success, empty data, or an infinite loading state.
- Removed paths are truly unreachable; aliases, old bindings, old workers, stale navigation destinations, or duplicate collectors cannot still run.

### Wiring red flags

- New function/class exists but no production call site reaches it.
- UI calls a new method, but an old collector/source still renders unrelated state.
- Repository writes one store while UI observes another.
- Fake/test binding works while production DI is missing or ambiguous.
- Debug variant works but release/flavor binding, resource, manifest, or generated code differs.
- Callback is registered but never unregistered, or result callback is installed after the operation can complete.
- Feature flag gates only part of the flow, creating mixed old/new behavior.
- Serialization defaults conceal a missing or renamed field.
- A successful side effect is followed by a failed local commit with no reconciliation path.

## 3. Architecture and ownership audit

Confirm the owner map remains singular and coherent:

```text
Canonical authority:
Observable local truth:
Upstream source(s):
Projection/state owner:
Mutation owner and commit point:
Freshness/refresh owner:
Cache owner/key/scope/invalidation:
Identity/account/tenant owner:
Lifecycle owner:
Concurrency owner/semantics:
Navigation owner:
Dependency-construction owner:
Error-mapping owner:
```

Check:

- UI renders and emits actions; it does not call DAO/API/DataStore or own durable work.
- ViewModel owns screen state and actions, not views, navigation framework objects, transport/database details, or process-global truth.
- Repository owns source selection, mapping, freshness, cache, reconciliation, and mutation semantics.
- DAO/API/DataSource owns boundary mechanics, not screen policy or navigation.
- Workers own durable idempotent work, not transient UI state.
- Dependencies point in the repository’s allowed direction.
- No second mutable copy, hidden singleton, service locator, mutable static, unmanaged cache, or alternate source of truth was introduced.
- Models are split only where boundary semantics require it; mappings are explicit and tested where loss is possible.
- Public contracts, routes, schema, wire fields, and module APIs changed only intentionally.

### No-overengineering gate

Every added abstraction must answer a concrete need:

- Which owner or boundary did it clarify?
- Which repeated policy, real reuse, transaction, platform split, or test seam requires it?
- Why is an existing class/function not sufficient?
- What complexity did it remove or contain?

Reject or remove:

- one-method forwarding use cases/services/repositories with no policy;
- interfaces with one implementation added only “for architecture”;
- generic wrappers around `Result`, Flow, Retrofit, Room, or navigation that erase useful semantics;
- new modules, event buses, global coordinators, caches, state machines, or DI scopes without measured need;
- speculative extensibility, broad cleanup, or framework migration unrelated to acceptance;
- multiple defensive mechanisms for the same race when one owned mechanism proves the invariant.

The audit should favor understandable direct code over ceremonial layering while preserving real boundaries.

## 4. Logic and state-machine audit

Reconstruct the states and transitions actually possible:

- initial, loading, content, empty, stale/offline, pending, success, partial success, error, retry, cancelled, permission denied, signed out, and restored states where applicable;
- trigger, guard, side effect, commit, state update, and terminal behavior for each transition;
- whether impossible combinations can occur through independent Booleans or duplicated state;
- whether validation is performed at the owner and again at untrusted boundaries where necessary;
- whether default branches hide new enum/sealed cases;
- whether nullable/empty/zero values have distinct domain meanings;
- ordering, equality, deduplication, pagination keys, inclusive/exclusive bounds, overflow, rounding, units, time zones, clocks, locale, Unicode, and stable identity;
- partial collections, duplicate IDs, missing records, deleted records, account changes, and stale references;
- success reported before authoritative commit or acknowledgement;
- failure paths that leave loading, disabled UI, locks, transactions, or pending records stuck.

Prefer a transition table or focused tests when state behavior is non-trivial. A branch being syntactically exhaustive does not prove its domain behavior is complete.

## 5. Truth, source, cache, and data-integrity audit

- Confirm one canonical authority and one documented observable truth per fact.
- Confirm source strategy: local-only, online-only, network-first, cache-first, or offline-first.
- Verify commit order. In an offline-first flow, remote data is not rendered truth until the local transaction commits.
- Verify multi-write invariants are transactional and transaction boundaries include metadata/checkpoints needed for consistency.
- Verify cache key includes all relevant dimensions: account, tenant, query, locale, permission, feature flag, version, or environment.
- Verify freshness, stale display, refresh ownership, invalidation, logout/account switch, and memory-pressure behavior.
- Verify optimistic/pending mutations have confirmed, failed, rollback/reconciliation, retry, and process-restoration semantics.
- Verify retry does not duplicate non-idempotent writes and unknown outcomes are reconciled before dangerous retry.
- Verify Room migrations preserve user data, schema identity, constraints, indices, defaults, and old-version upgrade paths.
- Verify Paging refresh/append/prepend keys, invalidation, duplicate pages, empty terminal pages, and mutation interaction.
- Verify DataStore/file writes are atomic enough for the invariant and no large/hot data was moved into an unsuitable store.
- Verify local success followed by remote failure, and remote success followed by local failure, have explicit outcomes.

## 6. Concurrency, race, deadlock, and duplicate audit

For every asynchronous operation, name the semantics: latest-wins, first-wins, single-flight, serialized, queued, coalesced, idempotent retry, or intentionally concurrent.

Exercise applicable races:

- double tap or rapid repeated action;
- old read completes after a new read;
- refresh overlaps pagination, mutation, logout, or account switch;
- multiple collectors trigger the same cold flow or initialization;
- screen is removed while work completes;
- cancellation occurs after a remote side effect but before local/state commit;
- worker/process retries after partial success;
- timeout leaves write outcome unknown;
- two entities incorrectly share a global lock or in-flight flag;
- same entity is mutated from UI and background sync;
- listener/callback fires during registration or teardown;
- clock/freshness boundary changes during the operation.

Check:

- stale completion cannot overwrite newer state;
- duplicate mutations are blocked at the mutation owner, not only by a UI loading flag;
- operation IDs/generations are used when cancellation is advisory;
- locks are scoped to the narrowest correct key and are not held across slow I/O without protocol justification;
- cancellation is rethrown/preserved and cleanup is in `finally`/`awaitClose`/owner disposal as appropriate;
- transactions/constraints/server idempotency protect invariants that an in-process mutex cannot;
- retries are bounded, classified, back off appropriately, and terminate;
- parallel child failure semantics intentionally use fail-together or supervision;
- main-thread blocking, nested blocking bridges, or dispatcher ping-pong was not introduced.

## 7. Loop, recursion, and repeated-work audit

Search for cycles that can self-trigger or never terminate:

- Compose state writes during composition or an effect keyed by the state it writes;
- `snapshotFlow`/Flow observer writes back to its observed source without a stable guard;
- Room observer triggers refresh that rewrites identical rows and invalidates itself repeatedly;
- retry/reconnect/polling loops without bounds, cancellation, delay/backoff, or terminal state;
- WorkManager work enqueues/replaces itself indefinitely or unique-work policy creates churn;
- navigation result/deep-link handling reopens the same destination;
- text/input normalization feeds back into the same callback and moves cursor/focus repeatedly;
- callback/listener recursion caused by programmatic updates;
- repeated initialization from recomposition, recreation, multiple entry points, or every collector;
- busy loops, hot flows with no subscribers policy, or timers that outlive their purpose;
- error handling that immediately retries a permanent failure.

Verify each recurring process has an owner, start trigger, stop condition, cancellation path, retry ceiling, and resource budget.

## 8. Lifecycle, cleanup, and leak audit

For every long-lived reference or active resource, verify owner symmetry:

```text
Created/registered in:
Released/unregistered in:
Survives screen removal?:
Survives recreation?:
Survives process death?:
Account/logout invalidation?:
Cancellation/exception cleanup?:
```

Check:

- Fragment view binding, adapters, listeners, callbacks, and view references end at `onDestroyView`.
- ViewModels do not retain Activity, Fragment, View, NavController, UI lambdas, or short-lived Context.
- Compose effects use stable keys, current callbacks, and `DisposableEffect`/lifecycle gating for registration cleanup.
- `callbackFlow`/listeners/receivers/observers/sensors/location/camera/media resources unregister in all paths.
- repository/app scopes are intentional, bounded, and contain no UI references or per-screen orphan collectors.
- `stateIn`/`shareIn` scope and sharing policy do not keep expensive upstream work alive indefinitely without need.
- WorkManager is used only for durable deferrable work and is unique/idempotent where retry can repeat effects.
- database cursors, streams, files, bitmaps, players, camera use cases, executors, and native resources close/release.
- logout/account switch clears or partitions credentials, caches, pending UI state, and active account-scoped work.
- process restoration rebuilds from stable IDs/durable truth rather than retained in-memory objects.

When leak risk is credible, inspect reference ownership and use available leak/profiler evidence; do not infer a leak solely from object size or a long-lived class name.

## 9. Error, security, privacy, and trust audit

- Exceptions are mapped at boundaries; cancellation is not swallowed.
- Broad catches do not convert defects, auth failures, data corruption, or programmer errors into empty/success states.
- User messages are safe and localized; logs/analytics/crash reports do not expose tokens, PII, server bodies, SQL, paths, or secrets.
- Incoming intents, deep links, URIs, extras, files, WebView messages, SDK callbacks, and AI/tool input are untrusted and validated.
- Authentication state, authorization, account/tenant identity, and entitlement are enforced at the authoritative boundary.
- Exported components, permissions, PendingIntents, URI grants, backups, screenshots, clipboard, and notifications remain least-privilege.
- Feature flags and debug tooling cannot bypass production trust boundaries.
- Failure and retry behavior does not reveal sensitive distinctions or amplify abuse.

Escalate security/data-loss/payment/account-crossing defects even when reproduction requires a narrow timing window.

## 10. Performance and resource audit

Look for correctness-affecting resource problems first:

- network/DB/DataStore work repeated per recomposition, collector, item, or retry;
- N+1 queries/calls, missing batching, unbounded lists/caches/buffers, or large object retention;
- main-thread disk/network/CPU, blocking locks, excessive serialization, or expensive transforms in hot UI paths;
- unstable lazy-list identity, unnecessary state writes, broad recomposition, image requests without size/lifecycle control;
- wakeups, polling, workers, sensors, location, camera, media, or sockets continuing without visible/product need;
- retry storms, reconnect storms, invalidation loops, and log flooding;
- release-only reflection/serialization/R8 issues that appear as crashes or missing data.

Do not recommend optimization from aesthetics. Require measurement for non-obvious performance claims and preserve correctness before speed.

## 11. Build, variant, release, and compatibility audit

When affected, verify:

- actual production source set and all relevant flavor/build-type implementations;
- DI graph, qualifiers/scopes, generated code, KSP/kapt outputs, and duplicate/missing bindings;
- merged manifest, exported flags, permissions, providers, authorities, deep links, services, receivers, workers, and placeholders;
- resource shrinking, names, locale/RTL variants, themes, icons, and configuration qualifiers;
- Room schema export/migration, serializer compatibility, API/wire fields, navigation route stability, and saved-state compatibility;
- minified release behavior, narrow keep rules, reflection/JNI/native loading, mapping output, and consumer rules;
- minimum/target SDK guards and behavior on supported API levels/form factors;
- dependency/toolchain changes are intentional, compatible, and not smuggled into a feature patch;
- material release risk has version-attributable, privacy-safe detection using existing infrastructure where available;
- staged rollout, rollback/disable, or forward-fix behavior is coherent with schema/data/backend compatibility;
- temporary flags, dual paths, compatibility adapters, and diagnostics have an owner and deletion condition.

A debug compile does not validate release wiring. A rollout plan does not replace pre-release evidence, and rollback must not be claimed safe when older code cannot consume the new data or contract.

## 12. Test and evidence quality audit

For every behavior-changing feature, logic change, or bug fix, audit the mandatory ten-category matrix from `android-testing-debugging`. The audit must not silently infer coverage from a green suite.

Record each row as `COVERED`, `N/A: <structural reason>`, or `UNVERIFIED: <missing runtime/device/infrastructure>`:

```text
Core logic:
State transitions:
Happy-path end to end:
Persistence + process death:
Failure + recovery:
Cross-feature interactions:
Concurrency / duplicate execution:
UI behavior:
Lifecycle / reboot:
Regression reproducer:
```

Rules:

- Every applicable row needs an automated oracle at the cheapest faithful boundary. One test may satisfy multiple rows only when it actually exercises each claimed failure mechanism.
- `N/A` means the category does not exist for this behavior, not that it was inconvenient to test.
- A new feature may mark Regression `N/A: no prior defect`; a bug fix may not.
- Ordinary CRUD may mark lifecycle/reboot N/A when process-death restoration is covered and there is no service/policy/alarm/permission/system owner.
- System-dependent behavior needs lifecycle/platform coverage; JVM-only fakes are insufficient when Android framework state is the risk.
- Missing execution infrastructure is residual risk. Do not report `PASS` for a material behavior change while an applicable critical row has neither a test nor an explicit blocking reason.

Select evidence capable of failing for the defect:

- focused unit tests for mapping, validation, transition, ordering, and boundary cases;
- controlled coroutine tests for stale completion, cancellation, duplicate actions, sharing, timeout, and retry;
- real Room/migration/serializer/fake-server integration where mocks would bypass the changed boundary;
- ViewModel tests for observable state/effects, not private implementation calls;
- Compose/host/instrumentation tests for semantics, lifecycle, navigation, permissions, and platform contracts;
- worker tests for uniqueness, constraints, retry, partial completion, and idempotency;
- release/minified/variant smoke checks when generated/reflection/manifest/resource behavior differs.

Audit the tests themselves:

- Would the test fail if the old bug returned?
- Does the fake reproduce the relevant boundary semantics?
- Are virtual time, dispatchers, clocks, IDs, ordering, locale, and random values controlled?
- Is a test passing because it asserts the implementation it just set up?
- Are assertions made on final observable behavior and protected invariants?
- Are flaky sleeps, broad retries, ignored exceptions, or global state masking failure?

Never report commands as run unless they actually completed and their result was inspected.

## AI-generated code hazards

Generated patches frequently look coherent while failing at integration. Search specifically for:

- plausible but unwired classes, methods, bindings, routes, workers, or resources;
- both old and new pipelines remaining active;
- invented APIs, dependency versions, Gradle DSL, manifest attributes, or framework behavior;
- a new abstraction that merely renames or forwards existing behavior;
- duplicated truth/state/cache or a mutable singleton introduced for convenience;
- “launch and forget,” unmanaged scopes, retained UI references, or lifecycle-oblivious collection;
- broad `catch`, `runCatching`, fallback-to-empty, fake success, or TODO behavior hiding failure;
- arbitrary delays, sleeps, retry loops, mutexes, flags, or operation IDs added without semantics;
- cancellation assumed to undo a remote side effect;
- tests that mock away the bug, duplicate production logic, or only prove compilation;
- warnings/suppressions/keep rules/nullable assertions added to silence evidence rather than fix ownership;
- debug-only wiring, missing release binding, or generated-code assumptions;
- comments claiming safety/idempotency/threading without an enforcing mechanism;
- massive refactors, formatting churn, framework migrations, or dependency additions unrelated to acceptance;
- speculative cleanup of pre-existing issues that raises regression surface;
- confident “fully fixed” language despite unrun lifecycle, release, migration, or adversarial checks.

Treat these as search prompts, not automatic findings. Confirm the actual failure mechanism before reporting or changing code.

## Post-change audit

Run this sequence automatically after implementation:

1. **Re-read the request and acceptance condition.** Confirm the patch solves that problem rather than a nearby one.
2. **Inspect the complete diff.** Include generated/configuration/manifest/schema/resource changes and remove unrelated churn.
3. **Map the live pipeline.** Trace trigger -> owner -> side effect/commit -> observable truth -> rendered/result consumer.
4. **Verify owners and boundaries.** Search for duplicate truth, bypassed repositories/DI, data-layer navigation, hidden globals, and old paths.
5. **Audit logic/state.** Exercise success plus empty/error/cancel/offline/pending/restoration branches that the change can affect.
6. **Run the race/loop/leak pass.** Check duplicate actions, stale completions, multiple collectors, self-triggering loops, teardown, account switch, and retry termination.
7. **Run focused evidence.** Use the repository’s smallest meaningful tests/build/lint/integration commands, then broader/release checks only when risk requires them.
8. **Inspect evidence and final diff again.** A passing command does not excuse incorrect scope, architecture, or an untested boundary.
9. **Fix and repeat narrowly.** Correct audit findings introduced by the patch with the smallest safe change and rerun the affected checks.
10. **Report honestly.** State findings, evidence, unverified areas, and residual risk. Do not claim perfection.

### Finding threshold and severity

Report actionable findings with evidence:

| Severity | Meaning |
|---|---|
| Blocker | likely data loss/corruption, security/privacy breach, wrong payment/entitlement, unrecoverable migration, or release-blocking crash |
| High | reproducible wrong behavior, race, duplicate mutation, lifecycle crash, serious leak/resource runaway, broken production wiring |
| Medium | credible edge-case defect, incomplete restoration/error/offline handling, fragile contract, localized performance/resource risk |
| Low | contained maintainability/test gap with a concrete future failure mode |

Do not inflate style preferences into defects. For each finding include:

```text
Severity and confidence:
Evidence/location:
Broken invariant:
Concrete failure scenario:
Smallest compatible correction:
Verification that would close it:
```

### Mandatory audit report

```text
Audit scope:
Requested outcome:
Observed trigger-to-result path:
Authority/truth/commit path:
Findings by severity:
Wiring and dead-path result:
Logic/state result:
Concurrency/race/loop result:
Lifecycle/leak result:
Data/security/release result:
Operational readiness/rollback result:
No-overengineering result:
Mandatory test matrix result:
Commands/tests/manual checks actually run:
Checks not run and why:
Residual risk:
Verdict: PASS / PASS WITH RESIDUAL RISK / FAIL
```

`PASS` means no material issue was found in the audited scope with the evidence actually run. It never means the entire application is defect-free.

### Evidence record

Record evidence compactly:

```text
Changed files/contracts inspected:
Callers/callees and bindings searched:
Old/duplicate paths searched:
Adversarial scenarios exercised:
Lifecycle/cleanup cases exercised:
Data/account/migration cases exercised:
Variants/release boundaries exercised:
Operational signals and rollback/forward-fix inspected:
Exact commands and outcomes:
Findings corrected and rechecked:
Unverified assumptions:
```
