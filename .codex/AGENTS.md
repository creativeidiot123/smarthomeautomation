# AGENTS.md — Production Kotlin/Android Contract

Production Kotlin/Android repository. Make the smallest correct change preserving architecture, user data, security, lifecycle, compatibility, and release behavior.

Default bias: **inspect first; one owner/truth; explicit source/cache/projection, async/lifecycle/duplicate semantics, contracts, and proof.**

Do not add dependencies, plugins, modules, wrappers, caches, layers, generated code, or runtime services unless materially required and no simpler repository-compatible pattern exists.

**Kotlin size gate:** new files stay ≤500 handwritten lines. Files already over 500: do not split them during unrelated work and do not grow them by more than ~30 lines; put new cohesive logic in a new file beside the owner. A split is its own task.

Repository evidence/instructions win. Gate depth follows the change tier in §17 (Tiny/Normal/Risky).

**Research gate:** when a change uses an API, library, or platform behavior this repo does not already use (or a version/OEM edge case), read official docs/source for the exact versions and search GitHub for implementations, issues, and regressions first. Repository contracts and official sources win. Never cargo-cult third-party code or architecture.

## Project golden rule

**Simple, functioning, efficient, stable code. Simpler, maintainable architecture.**

Avoid over-engineering, unnecessary abstractions, duplicated ownership, hidden state, fragile workarounds, leaks, loops, races, and unnecessary complexity.

## 0. Destination quick reference

- **App:** `:app` + `:baselineprofile`; digital-wellbeing enforcer (Device Owner policy, Firestack VPN DNS/website blocking, Shizuku, AccessibilityService, budgets, Pomodoro, App Openings).
- **Has:** Compose/Navigation/M3, Room `FocusDatabase`, WorkManager, kotlinx.serialization, CameraX, ONNX, AdMob + UMP. **Has not:** own backend/API, user accounts, Hilt/Koin, Retrofit/Ktor, Paging, Truth/MockK; account/tenant/token/pagination/server rows are N/A unless the task adds them.
- **Dependencies:** `DestinationApplication` is the only composition root. ViewModel factories (`SavedStateViewModelFactory`) and workers/receivers/services resolve it via `AppServices.destinationApplicationOrNull(context)` (see `AppRulesViewModelFactory`, `PolicyReconcileWorker`). Never construct repositories/stores/databases elsewhere; never add a DI framework.
- **Reuse:** `runCatchingNonCancellation`, `SystemPolicyClock`, `FocusLog` + `FocusEventId`, `collectAsStateWithLifecycleCompat`/`CollectLatestWithLifecycle`, `FocusMigrations.ALL`. Grep for a helper before writing one.
- **Hot zones (Risky):** `policy/`, `network/`, `boot/`, `provisioning/`, `recovery/`, `antiaddiction/`, Room schema, Service/Receiver/Worker code, device-protected storage, Gradle/release config.
- **Guard tests (never weaken):** `BrutalArchitectureGuardrailTest` (bans `GlobalScope`, `runBlocking`, `Thread.sleep`, `!!`, `collectAsState(`, destructive migration, direct `Log`), `ArchitectureOwnershipStaticTest`, `AgentSkillPackStaticTest`.
- **Commands (Windows, one Gradle run at a time, add `--no-daemon --console=plain`):** `.\gradlew.bat :app:compileDebugKotlin`; `.\gradlew.bat :app:testDebugUnitTest --tests "<FQCN>"`; `.\gradlew.bat brutalCheck`.

## Operating behavior

### Think before coding

**Do not assume or hide ambiguity.**

Before implementing:
- State material assumptions. Ask before choices affecting data loss, security, money, identity/authorization, irreversible behavior, public contracts, retention, or major UX.
- For reversible low-risk ambiguity, choose the smallest compatible behavior and record it.
- Surface simpler tradeoffs; never invent backend guarantees, authorization/conflict/retention policy, or destructive behavior.

### Simplicity first

**Minimum code; nothing speculative.**

- No unrequested features, one-use abstractions, or speculative configurability.
- Do not defend states impossible by types/invariants. Treat persisted/platform/IPC/network/route/file/SDK boundaries as fallible and map failures at their owner.
- If 200 lines could be 50, rewrite it.

### Surgical changes

**Touch only what the request requires.**

- Do not improve adjacent code, comments, formatting, or architecture.
- Match existing style. Mention unrelated dead code; do not remove it.
- Remove only imports/variables/functions made unused by your change.
- Every changed line must trace to the request.

### Goal-driven execution

Turn work into verifiable goals: reproduce a bug before fixing it, test invalid inputs before adding validation, and preserve before/after behavior during refactors. For defects, find the first bad state/root cause at its owner; never mask it with arbitrary delay/retry/refresh/reset, broad catch, suppression, or stacked guards unless that mechanism is itself justified by the operation semantics and evidence.

For multi-step work, state `step -> verification` briefly and loop until defined checks pass. Weak goals such as “make it work” require clarification.

## Product contract gate

For non-trivial features, use `android-product-contract` before implementation: goal/non-goals/journeys, owners/truth, data lifecycle, duplicate/retry/undo/unknown outcomes, restoration/permissions/security/privacy/platform constraints, and acceptance/forbidden outcomes as observable scenarios. Record low-risk assumptions.

## 1. Always-on invariants

These apply even to small fixes:

1. **One authority per mutable fact.** Name who may decide and mutate it.
2. **Truth differs from source, cache, and UI state.** Server may be upstream authority, Room local truth, and ViewModel a projection.
3. **Every async operation has semantics:** latest-wins, first-wins, single-flight, queue, merge, idempotent retry, or concurrent.
4. **Every operation has a lifecycle owner** and intentional stop/survive/transfer behavior.
5. **Every mutation defines duplicate/retry behavior** across taps, re-collection, retries, and recreation.
6. **Every cache defines key, owner, freshness, identity scope, invalidation, and removal.**
7. **Every boundary maps models/errors deliberately;** transport, DB, framework, and sensitive details do not leak upward.
8. **UI renders state and emits actions;** it owns no repository/API/DAO/DataStore/durable work/business truth.
9. **Navigation is a host/UI-edge effect;** data/domain layers never navigate.
10. **Compilation is not proof.** Run a focused `android-post-change-audit` after implementation; verify wiring, behavior, races, lifecycle, and contracts proportional to risk.
11. **Every edit classifies persistence impact before implementation** (Room entities/DAOs/database, DataStore, files, serialized backups). Possible schema change: inspect DB version, exported schema history, migration chain, and every production builder first; see §10.

If an invariant is unclear, inspect rather than inventing a convenient owner.

## 2. Instruction and skill use

**Pre-edit gate (scaled by §17 tier):** Tiny: read the target file and its direct callers. Normal/Risky: also read the relevant `project.md` and `architecture.md` sections (by heading), the matching skills (usually 1-3), and enough callers/callees/tests/wiring to identify the live owner. Search for an existing owner/helper/pattern before creating one. Do not edit until dependency direction, invariants, production path, and persistence impact are understood; ambiguity means inspect, not guess.

For conflicts prefer: user-data/security safety; repository architecture/versions; lifecycle/cancellation/retry/recreation correctness; smaller verifiable changes; fewer assumptions/dependencies.

Never let a specialist skill silently trigger a broad migration. Version-specific or experimental guidance applies only when the project already uses that stack or the user explicitly requested the migration.

### Living project documentation

`project.md` and `architecture.md` are implementation contracts. Update `project.md` for verified repository/toolchain/build/schema/integration fact changes; update `architecture.md` for ownership, truth, dependency, lifecycle/concurrency, persistence, navigation, DI, background-work, or enforcement changes. Record why unchanged for Normal/Risky patches. Code beats stale prose; exclude secrets, local paths, transient status, and plans.

### Skill routing

Routing is additive: read all matching rows, not only first. Skill checklists/evidence records are aids; the required report is §17's.

| Work | Skill |
|---|---|
| Product contract/raw idea | `android-product-contract` |
| Post-change audit | `android-post-change-audit` |
| Architecture/Kotlin | `android-architecture-ownership` |
| State/navigation/lifecycle | `android-state-navigation-lifecycle` |
| Coroutines/Flow/work | `android-coroutines-flow-work` |
| Data/offline/paging/Room schema migrations | `android-data-room-sync-paging` |
| Network/auth transport | `android-network-auth-reliability` |
| Compose / Material 3 / M3 Expressive | `android-compose-ui-effects` + `m3-expressive` |
| UX/accessibility/adaptive | `android-accessibility-adaptive-ui` |
| Testing/debugging | `android-testing-debugging` |
| Performance/R8 | `android-performance-release` |
| Gradle/DI/R8/release | `android-gradle-di-release` |
| Google Play Console/policy/distribution | `android-google-play-developer` |
| Auth/identity/security/privacy | `android-security-privacy-identity` |
| Framework/API/technology migrations | `android-migration-modernization` |
| KMP/CMP | `android-kmp-compose-multiplatform` |
| Platform/media/camera | `android-platform-camera-media` |
| Play Billing/Engage | `android-play-billing-entitlements` |
| Wear OS | `android-wear-os` |
| Android XR/Glimmer | `android-xr-glimmer` |
| AppFunctions/device AI | `android-appfunctions-device-ai` |
| Implementation patterns | `android-implementation-patterns` |

## 3. Authority, truth, and source model

Distinguish these before changing behavior:

| Concept | Meaning | Examples |
|---|---|---|
| Canonical authority | Decides facts and accepts/rejects mutations | server record, local-only Room table, DataStore setting |
| Upstream source | External producer; may or may not be authoritative | REST API, SDK callback, system provider |
| Local truth | Observable local record rendered by the app | Room query, DataStore Flow, session holder |
| Cache | Discardable acceleration copy | memory/disk response or image cache |
| Projection | Derived read-only representation | `UiState`, filtered/formatted data, badge count |
| Pending intent/outbox | Durable unconfirmed mutation | sync queue, pending upload row |
| System truth | Android/other-process state | permission, connectivity, notification setting |

Multiple sources are valid; multiple undecided authorities are not. Divergence requires reconciliation, freshness, conflict, and pending-write policy.

### Required owner map

For non-trivial work, resolve:

```text
Feature/operation; product policy owner
Canonical authority; observable local truth; upstream sources
Projection/state owner; mutation owner; freshness/refresh owner
Cache owner/key/scope/invalidation; identity/account/tenant owner
Lifecycle owner; concurrency semantics; navigation owner
Dependency construction owner; test oracle
```

Rules:
- ViewModel may own screen state, not durable authority.
- In offline-first flows, an API response is not committed truth until the local transaction succeeds.
- Memory is not truth merely because it is fast.
- Repository owns source/reconciliation strategy; UI never chooses API versus DB.
- Recompute derived values from their owner or invalidate them with it; do not synchronize duplicate copies manually.
- Include account, tenant, locale, permission, environment, and feature-flag context in keys/invalidation when relevant.
- Optimistic writes model pending/confirmed/failed/rollback/retry; do not claim permanent success before authoritative outcome unless policy accepts it.
- If remote success precedes local persistence failure, retain reconciliation evidence.
- Treat time as a dependency for expiry/order/backoff/freshness/date boundaries; use the project clock when available.

### Source strategy must be explicit

Choose and preserve one read strategy: local-only, online-only, network-first with defined fallback, cache-first with freshness/invalidation, or offline-first with local truth, transactional refresh, and pending/conflict policy. Room alone does not make a design offline-first.

## 4. Lifecycle and scope model

Every reference, collector, listener, coroutine, cache, and worker has a scope owner and termination rule.

| Owner | Appropriate work | Must not retain |
|---|---|---|
| Composition | composition-bound UI effects | Activity/View beyond composition; durable work |
| Fragment view lifecycle | binding, collectors, adapters/listeners | view after `onDestroyView` |
| Activity lifecycle | results, permissions, system UI | feature work that should survive recreation without transfer |
| ViewModel | screen state/cancellable operations | Activity, Fragment, View, NavController, UI-lifetime Context |
| Repository/app scope | explicitly shared process work | UI references, unbounded per-screen collectors |
| WorkManager | durable, deferrable, retryable, constrained work | navigation, screen state, unsafe non-idempotent retry |

Rules:
- Use structured concurrency; parent owns child cancellation/completion.
- Collect UI state/effects only while the relevant lifecycle is active.
- Register/unregister callbacks in the same owner; cleanup on cancellation and disposal.
- Never retain views, Compose lambdas, Activity contexts, adapters, or callbacks in longer-lived objects.
- Configuration recreation is not process survival. Use `SavedStateHandle`, durable storage, or authoritative reload by importance; save stable IDs/minimal reconstructible input, not large/domain objects.
- Transfer work that must outlive the screen to an app owner or WorkManager. Do not lengthen scope for work that should stop on leave.
- Logout/account switch cancels or invalidates scoped work, collectors, caches, pending UI state, and credentials.
- Process initialization is idempotent; avoid duplicate observers/work from multiple entry points.

Test affected lifecycle boundaries: leave/return, recreate, background/foreground, cancel, account switch, or process restore.

## 5. Concurrency and operation semantics

Choose semantics per operation:

| Operation | Typical default | Protection |
|---|---|---|
| Changing-input read/search | latest-wins | cancel/ignore obsolete completion |
| Manual refresh | single-flight or latest-wins | de-duplicate; define stale display |
| Submit/payment/order/upload | first-wins or queue | guard duplicates; idempotency key when supported |
| Toggle/favorite | serialize per entity or coalesce final intent | reconcile failures |
| Pagination append | one in-flight per key/direction | prevent duplicate pages/key races |
| Background sync | idempotent retry/merge | unique work, transactions/checkpoints, bounded retry |
| Independent reads | concurrent | intentional aggregation/partial-failure policy |

Rules:
- Prior-state updates are atomic.
- Cancellation blocks obsolete UI commits, but does not prove a remote write did not happen.
- Use operation IDs/generations when the source cannot cancel or callbacks may arrive late.
- Serialize at the narrowest key; avoid global locks for unrelated work and locks across remote I/O unless required.
- Multi-write DB invariants use transactions. Cross-process/server invariants use constraints, unique work, idempotency, or server enforcement—not an in-process mutex.
- Multiple collectors must not trigger accidental refreshes/mutations; Flow temperature, sharing scope, and replay are deliberate.
- Retry only bounded transient, idempotent, or protected work. A write timeout may be unknown outcome; reconcile before dangerous retry.
- Backpressure is explicit; never silently drop business-critical events through conflation/replay/buffer overflow.

### Mandatory race audit

```text
Double tap/repeated action; older read after newer read
Refresh vs pagination/mutation; screen destroyed before completion
Account/tenant change in-flight; worker retry after partial success
Remote success before local commit failure; duplicate collectors/entry points
Cancellation between side effect and state update; freshness boundary change
```

Use the smallest mechanism proving the semantics; do not stack guards without distinct reasons.

## 6. Execution protocol

For non-trivial changes:

1. **Resolve contract:** observable acceptance, forbidden outcomes, invariants, assumptions, external guarantees.
2. **Inspect:** modules/source sets, DI, navigation, state/events, persistence, network, errors, tests, build conventions, versions.
3. **Map owners:** policy, authority, local truth, sources, projection, mutation, freshness, cache, lifecycle, concurrency, navigation, dependency, tests.
4. **Define states:** success/loading/empty/error/retry/cancel/stale/offline/duplicate/permission/recreation as relevant.
5. **Patch the nearest owning seam;** do not route around it from UI or create a parallel owner.
6. **Preserve/migrate contracts:** find affected producers/consumers before changing routes/deep links, schemas/serialized fields/DTOs, exported components, APIs, Gradle coordinates, or user-visible behavior.
7. **Implement minimally** with existing DI, dispatchers, mappers, errors, and tests.
8. **Verify, audit, and report per §17/§18;** fix audit findings narrowly, no speculative cleanup. Debug from evidence, not trial-and-error.

## 7. Architecture boundaries

Default direction:

```text
UI -> ViewModel -> Repository -> Room/API/DataStore/File
```

Use a UseCase/Interactor/Service only for shared business policy, multi-repository orchestration, complex validation/calculation, transaction-like workflow, or a valuable test seam. A class that only forwards one call is noise.

| Layer | Owns | Must not own |
|---|---|---|
| Composable/View UI | rendering, UI-local mechanics, actions, semantics | API/DAO/DataStore calls, durable truth, business mutation, long-lived cache |
| Activity/Fragment | hosting, lifecycle, result/permission launchers, system integration | normal feature business rules, retained view references |
| ViewModel | immutable screen state, actions, validation, screen async, UI-safe errors/effects | Activity/Fragment/View/NavController, DAO/API details, durable cache truth |
| Repository | source strategy, freshness, mapping, mutation semantics, offline/conflict policy | screen state, navigation, UI strings/components, lifecycle references |
| DAO/API/DataSource | boundary mechanics and transport/storage contracts | screen policy, navigation, cross-feature orchestration |
| Worker | durable idempotent work, constraints, progress/retry | navigation, screen state, unsafe repeated mutation |

Preserve safe existing architecture; never force a pattern/framework because a skill mentions it. Dependency construction stays in the existing DI/composition root; do not bypass it from UI/ViewModel.

## 8. State, events, and navigation

- Expose immutable state; keep mutable state private.
- Model exclusive modes so impossible state combinations cannot occur.
- Durable outcomes belong in durable/replayable state or source of truth. Event streams are for transient presentation effects.
- No in-memory event stream guarantees exactly-once delivery across process death.
- Navigation executes at the UI/host edge. ViewModels may expose typed intents/state, but never hold UI/navigation framework objects.
- Pass stable IDs/minimal arguments and reload from the owner after recreation.
- Validate deep links and route arguments; re-check authentication/authorization at the destination.
- Prevent duplicate destinations when repeated actions are possible.
- Permission and Activity Result launchers stay at the UI edge; ViewModel owns decision and result state.
- UI loading state is not a concurrency protocol; the mutation owner protects duplicate execution.

## 9. Coroutines, Flow, and background work

- No `GlobalScope`, unmanaged scope/thread, main-thread blocking, or orphan job.
- Scope work to composition/lifecycle, `viewModelScope`, an explicitly owned app/repository scope, or WorkManager.
- The boundary performing blocking I/O owns dispatcher switching. Avoid redundant `Dispatchers.IO` around main-safe Room/Retrofit suspend APIs.
- Never swallow `CancellationException`. `runCatching` in suspend paths must preserve cancellation.
- Use `async` only for true concurrency and always await/aggregate. Choose `coroutineScope` versus `supervisorScope` by failure semantics.
- Callback streams use cancellation-aware bridges and unregister reliably.
- Choose Flow operators by semantics: latest for cancellable reads, not ambiguous writes; `combine` for latest values; `zip` for pairwise values.
- Define `stateIn/shareIn` scope, start policy, replay, and stop behavior deliberately.
- Avoid per-collector refresh/mutation unless explicitly intended.
- WorkManager jobs are idempotent, constrained, uniquely named where duplication matters, and persist checkpoints required for safe retry.

## 10. Data, cache, sync, Room, and Paging

Every repository read/write path defines:

```text
Authority and observable truth
Read strategy and freshness
Write/commit path
Offline and stale behavior
Refresh trigger and de-duplication
Conflict and pending-write policy
Retry/idempotency
Cache key/scope/invalidation
Account/logout behavior
```
- Use Room as observable local truth when the product/repository requires it, not as a reflex for transient online-only data.
- Separate DTO, Entity, Domain, and UI models when contracts differ; map explicitly at boundaries.
- Atomic multi-write invariants use DAO `@Transaction` or `RoomDatabase.withTransaction`.
- Never use destructive migration for user data without explicit approval; migration changes require tests.
- Avoid N+1 queries, unbounded reads, missing hot-path indexes, per-collector refresh, and replace-all logic that destroys pending edits.
- Store relational/list data in a database, small preferences/config in DataStore, and secrets in approved secure storage.
- Cache keys include every result dimension and identity context. Invalidate on logout/account/tenant/environment changes.
- Sync records pending/failed/deleted/conflicted state as required; never silently discard local edits.
- Paging has one owner for load keys and in-flight direction; UI handles load states and retry without launching duplicate loads.
- Commit one visible local state in one DB transaction. Across remote/local boundaries, define commit order, idempotency, pending intent/outbox when needed, and reconciliation; network I/O and a local transaction are never one atomic commit.

### Persistence/schema gate

Classify persistence impact for every edit. Unchanged schema: never bump the DB version or add ceremonial migrations. Changed schema: follow the **Destination-specific Room schema evolution gate** in `android-data-room-sync-paging` in full: shipped schema JSON is immutable; bump `N -> N+1` once; deterministic non-destructive migration registered in every production builder; export/review the new schema; test fresh create, `N -> N+1`, and shipped-origin -> current on a real Room boundary; define rollback/forward-fix. Unrun device migration proof is reported unverified.

## 11. Network, authentication, and errors

- Centralize base URL, serialization, timeouts, auth headers, safe logging, and token refresh in existing network infrastructure.
- Token refresh is single-flight, avoids recursive interceptor loops, and invalidates the session cleanly when impossible.
- Map transport/status/serialization errors to stable domain failures at the data boundary. Raw exceptions and DTOs do not leak to UI.
- Preserve cancellation and distinguish unknown outcomes for timed-out writes.
- Retry only bounded transient/idempotent or protected operations; respect server hints.
- Validate unknown enum values, nullability, pagination, clocks/time zones, and backward-compatible fields.
- Never log bodies, headers, tokens, cookies, credentials, personal/payment data, visited domains, QR/NFC payloads, location, or unredacted identifiers.
- Every failure path logs one `FocusLog.w/e` line with a `FocusEventId`: operation, exception type/reason, and the key IDs/counts/state behind the decision.
- Client state never becomes authority for payment, entitlement, identity, integrity, or privileged access; server verification owns those decisions.

## 12. Compose, views, UX, and accessibility

- UI renders state and emits actions; no repository/API/DB work from composition or view callbacks beyond dispatching actions.
- Keep business state in ViewModel and ephemeral mechanics in `remember`/`rememberSaveable` or view-local state.
- Collect state/effects with lifecycle awareness; choose effect keys intentionally and clean listeners/resources.
- Never write state during composition or perform expensive transforms in hot composition/list item paths.
- Public reusable composables accept `Modifier`; leaf components do not receive ViewModels.
- Lazy lists use stable keys where identity survives reorder.
- Preserve the design system; support font scaling, RTL, long text, keyboard/focus, semantics, target sizes, and gesture alternatives.
- Use real insets, not fixed status/navigation bar approximations; avoid double consumption.
- Test loading, empty, error, disabled, large-text, IME, resize/fold, and edge-to-edge states when affected.

## 13. Security, privacy, storage, and platform boundaries

- Treat manifests, exported components, intents/deep links, PendingIntents, providers/URI grants, WebViews, notifications, clipboard, backups, telemetry, and screenshots as exposure boundaries.
- Export only when required; validate caller/input and enforce permission/authentication at the receiving boundary.
- Prefer explicit intents; sanitize incoming URIs/actions/extras. Never trust client-provided authorization decisions.
- PendingIntent mutability is minimal and explicit; immutable by default.
- Use content URIs/FileProvider and narrow temporary grants; never expose raw paths.
- Restrict WebView JavaScript/file access/mixed content/redirects/bridges unless required and reviewed.
- Secure storage protects secrets at rest; it does not make the client authoritative.
- Request least privilege at point of need and handle denial, permanent denial, settings changes, and degraded behavior.
- UI messages are safe/localized; never expose stack traces, raw server bodies, SQL, paths, tokens, or internal identifiers.

## 14. Build, DI, performance, and release

- Preserve existing catalog, plugins, repositories, DI framework/scopes, module boundaries, toolchain, and generated-code approach. No incidental AGP/Gradle/Kotlin/Compose/KSP/SDK/Navigation/Room/billing upgrades.
- DI follows lifecycle; no UI object in singleton scope, duplicate binding, hidden service locator, or manual construction around the graph.
- Never disable lint/tests/shrinking/warnings/security checks globally to pass a build, and never place secrets in source/resources/manifest/catalog/Gradle/BuildConfig expecting secrecy.
- Measure before optimizing. Verify affected variants/release/minified behavior when reflection, serialization, schemas, navigation, background work, SDKs, or packaging changed; keep R8 rules narrow and preserve shipped mappings.
- For material release risk, use `android-gradle-di-release`/`android-performance-release` to define compatible rollout, intervention signals, rollback/disable or forward-fix behavior. Do not call rollback safe across irreversible schema/server/data-format changes without evidence.

## 15. Tests and debugging

Every behavior-changing feature, logic change, or bug fix gets at least one test that fails without it. Coverage depth follows the §17 tier: Risky assesses every row below; Normal covers rows 1 and 10 plus any row the change touches; Tiny/docs are exempt. Applicable rows need automated proof; `N/A` needs a structural reason; missing device/infrastructure is `UNVERIFIED`. One test may cover multiple rows.

```text
1 Core logic: rules, boundaries, invalid/conflicting inputs, outputs.
2 State transitions: relevant inverse, permission, and expiry transitions.
3 Happy-path E2E: real trigger/UI -> owners -> persistence/platform -> observed result.
4 Persistence/process death: recreate from durable truth, not memory.
5 Failure/recovery: break the key dependency, fail safely, then recover.
6 Cross-feature: meaningful collision points only.
7 Concurrency/duplicates: rapid/repeated/overlapping work obeys declared semantics.
8 UI behavior: controls, validation, loading/error, navigation/restoration reflect truth.
9 Lifecycle/reboot: mandatory for system-owned features; ordinary CRUD may be N/A with reason.
10 Regression: every fixed behavior bug gets a permanent reproducer test.
```

Use the cheapest faithful unit/coroutine/ViewModel/Room/worker/UI/instrumentation/release boundary. Tests must fail when the defect/invariant breaks; never weaken tests or gates to turn green. See `android-testing-debugging` for the detailed matrix and `android-post-change-audit` for enforcement.

## 16. Anti-pattern radar

Reject or isolate these unless a documented legacy constraint requires them:

| Hazard | Safer direction |
|---|---|
| UI calls DAO/API/DataStore | action -> ViewModel -> repository |
| repository/use case navigates | typed result/state -> UI host |
| duplicate mutable copies of same fact | one authority + derived projections |
| API response and Room both treated as truth | explicit source strategy and commit order |
| public mutable Flow/LiveData/list | private mutable owner + immutable view |
| Boolean “navigate once” flag | durable state or lifecycle-aware transient effect |
| `GlobalScope` / unmanaged scope | explicit lifecycle owner or WorkManager |
| broad catch swallows cancellation | specific handling; rethrow cancellation |
| `flatMapLatest` for unsafe write | queue/single-flight/idempotency protocol |
| refresh per collector/recomposition | refresh owner + de-duplication |
| mutable singleton cache | scoped cache with key/TTL/invalidation |
| optimistic success without reconciliation | pending/confirmed/failed protocol |
| mutex assumed to protect server/process invariant | DB/server/idempotency/unique-work enforcement |
| destructive Room migration | explicit migration + test |
| raw exception/server text in UI/log | safe mapping and redaction |
| broad R8 keep rules | minimal rules + minified verification |
| fake success after failure | explicit error/stale/pending state |

## 17. Change tiers and completion

| Tier | Applies to | Done means |
|---|---|---|
| Tiny | copy/strings, comments, styling, rename inside one file | Compiles. `Room schema: unchanged`. |
| Normal | logic/state/UI behavior outside §0 hot zones | Tiny + grep proves new code is called and any replaced path has 0 callers + a focused test that fails without the change + `:app:testDebugUnitTest`. |
| Risky | §0 hot zones, schema, concurrency, services/receivers/workers, permissions, release config | Normal + `android-post-change-audit` + applicable §15 rows + `brutalCheck`. Device-only proof is listed as UNVERIFIED. |

Every tier: no parallel truth, UI data access, data-layer navigation, needless abstraction/upgrade, unrelated cleanup, debug code, secrets, suppressions, or accidental contract changes.

Final report (≤10 lines): changed behavior; owner touched and why; exact commands + results; not verified; `Room schema: changed/unchanged`; `project.md`/`architecture.md` updated or why not; residual risk.

## 18. Proof of change

Done only when evidence shows intended behavior changed, protected behavior did not, and no material audit finding remains. Compilation is necessary, not sufficient. Evidence must fail if the defect returns (revert and rerun when practical). Verify in proportion to risk: static (diff, reachability, stale old paths, contracts) -> deterministic (failure, cancellation, duplicate, retry, restoration) -> real boundary (DB/serializer/worker/framework) -> lifecycle/adversarial -> affected variants/minified release. Never claim fully verified/production-ready/fixed when material checks were not run or proof is compilation-only.
