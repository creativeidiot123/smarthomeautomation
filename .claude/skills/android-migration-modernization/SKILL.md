---
name: android-migration-modernization
description: "Use for incremental Android technology migrations/upgrades: XML→Compose, LiveData→StateFlow, RxJava→coroutines/Flow, CameraX, Navigation, Room, AGP/Kotlin/KSP. Preserve behavior and rollback."
---

# Migrations and Modernization

## Migration rule

A migration is an explicit product/engineering task, not incidental cleanup. Preserve behavior first, then improve structure in separately reviewable steps.

Before changing technology:

```text
Current stack/version:
Target stack/version:
Reason and expected benefit:
Public/schema/route/user-data contracts:
Behavior and visual baseline:
Interop period:
Rollback point:
Verification per step:
Known unsupported/experimental APIs:
```

Do not migrate multiple independent axes in one patch unless unavoidable (for example, AGP/Kotlin compatibility). Avoid “while here” Hilt, module, navigation, DB, UI, and dependency rewrites.

## General workflow

1. Inventory usages and owners.
2. Capture tests/screenshots/benchmarks and edge states.
3. Introduce target dependencies/config only as needed.
4. Add an interoperability seam.
5. Migrate one vertical slice/call path.
6. Compile/test immediately.
7. Remove old path only after no usage and behavior parity.
8. Run release/minified/migration verification.
9. Document remaining legacy boundary and cleanup plan.

Keep commits/patch phases buildable when practical.

## XML Views to Compose

Use incremental adoption unless a full screen/module migration is explicitly scoped.

### Candidate selection

Prefer a cohesive screen/component with:

- clear state/callback boundary;
- screenshot/behavior coverage;
- limited custom View/animation/accessibility complexity;
- no hidden Fragment/Activity business logic;
- manageable theme interoperability.

### Baseline

Capture:

- loading/content/empty/error/disabled;
- light/dark and relevant dynamic/brand theme;
- large font/RTL;
- keyboard/insets;
- scroll position and interactions;
- compact/expanded sizes;
- accessibility semantics/focus;
- visual screenshot if framework exists.

### Interop

Compose in Fragment/View:

- correct Compose dependencies/compiler for project;
- theme bridge only for candidate, not whole-app theme rewrite;
- `ComposeView` composition strategy tied to View tree lifecycle (commonly `DisposeOnViewTreeLifecycleDestroyed`);
- Fragment binding cleared;
- state collected lifecycle-aware;
- callbacks route to existing ViewModel;
- no new repository access from Composable.

Views in Compose:

- `AndroidView` factory/update separation;
- listener/resource cleanup;
- preserve saved state/accessibility/measurement;
- avoid recreation on every state change.

### Mapping

- LinearLayout -> Row/Column;
- Frame/overlap -> Box;
- RecyclerView -> Lazy list with stable keys;
- visibility gone -> conditional composition;
- ViewBinding click -> callback/action;
- selector/state list -> Material/design-system component/state;
- XML dimensions/colors/text -> existing tokens/resources;
- custom View -> Canvas/custom Layout only after understanding behavior.

Do not mechanically translate every wrapper or XML constraint. Preserve effective behavior, accessibility, and visual hierarchy.

Always include a preview/sample when repository convention supports it. Remove XML/style/drawable only after references are gone and parity verified.

## LiveData to StateFlow

- identify lifecycle/initial-value/distinct/replay semantics;
- expose private mutable/public StateFlow or derive with `stateIn`;
- choose initial state deliberately—do not use fake null/default that creates invalid UI;
- replace `observe` with lifecycle-aware collection;
- preserve MediatorLiveData/switchMap semantics using `combine/flatMapLatest` as appropriate;
- event LiveData does not become StateFlow automatically; classify durable state versus effect;
- update tests to coroutine scheduler;
- migrate one screen/owner at a time;
- remove adapters only when all consumers are migrated.

Avoid collecting Flow in ViewModel only to assign another flow unless transformation/ownership requires it.

## RxJava to coroutines/Flow

Follow the semantic audit in coroutines skill.

- Single/Maybe/Completable -> suspend result/null/typed outcome;
- Observable/Flowable -> Flow with preserved hot/cold/backpressure/replay;
- Subjects -> StateFlow/SharedFlow/Channel according to state/broadcast/queue;
- schedulers -> boundary dispatcher ownership;
- Disposable lifetime -> structured scope;
- switchMap/concatMap/flatMap semantics preserved;
- retry/repeat/error terminal behavior preserved;
- cancellation bridges cancel upstream;
- avoid mixed Rx/coroutine double-subscription during interop;
- use official interop adapters temporarily when already available/justified;
- remove dependency only after no runtime/test/generated use.

Add equivalence tests for ordering, errors, cancellation, hot replay, and backpressure.

## Camera1/Camera2 to CameraX

Migrate only if feature scope and device requirements support CameraX.

- inventory preview, capture, analysis, video, focus, zoom, flash, rotation, output, permissions, lifecycle, device quirks;
- add compatible CameraX artifacts through existing catalog;
- bind use cases to LifecycleOwner with ProcessCameraProvider;
- do not manually open/close in `onResume/onPause` when lifecycle binding owns it;
- use PreviewView for Views or supported Compose viewfinder for installed version; interop is acceptable if native API unavailable;
- use metering point APIs for focus instead of custom coordinate matrices;
- close every `ImageProxy` in `finally`;
- choose analysis backpressure (`KEEP_ONLY_LATEST` etc.) and executor/dispatcher;
- account for rotation/aspect ratio/mirroring/front camera;
- save through MediaStore/file owner and handle scoped storage;
- unbind/rebind atomically when switching camera/use cases;
- test permission denial, unavailable camera, background/foreground, rapid capture, rotation, low storage, analyzer failure.

Do not blindly require a specific CameraX version; check current official compatibility and project compile SDK.

## Navigation 2 to Navigation 3

Only by explicit request and after checking stable/experimental status and Compose/View constraints.

- inventory routes, graphs, nested graphs, deep links, multiple back stacks, dialogs/sheets, results, ViewModel scoping, saved state, transitions, adaptive panes, Hilt/DI;
- capture deep-link and back-stack tests;
- define typed route/NavKey serialization and restoration;
- pass IDs, not objects;
- migrate one flow while preserving old host through a clear seam if possible;
- preserve ViewModel store/scoping and result semantics;
- test process recreation, cold/warm deep links, multiple back stacks, conditional auth, list-detail compact/expanded;
- do not introduce Navigation 3 merely because an adaptive skill mentions it.

## Room upgrades

- inspect generated API/package changes, driver/support SQLite, KMP implications, KSP processor, schema export, converters, migrations, transaction APIs;
- never change schema and Room major version without migration proof;
- preserve database filename/encryption/journal/pragmas/callbacks;
- migrate generated imports/DAO APIs in buildable steps;
- test all supported schema migrations and release minification;
- do not switch Room to SQLDelight or vice versa incidentally.

## AGP/Kotlin/KSP modernization

Use Gradle skill. Important migration discipline:

- compatibility matrix first;
- Android-only recipes may not support KMP;
- built-in Kotlin/new DSL/namespace/source-set changes may be breaking;
- KSP migration only when every processor supports target;
- update test/screenshot/benchmark plugins;
- remove obsolete flags, do not add permanent compatibility escape hatches;
- build configuration then debug then tests/lint then release;
- no mass Kotlin syntax rewrite solely because compiler version changed.

## Edge-to-edge modernization

- inspect existing system UI control and target SDK behavior;
- replace deprecated accompanist/system bar hacks only in scoped migration;
- establish one inset owner;
- migrate screen-by-screen with keyboard/navigation mode/cutout tests;
- avoid visual “fix” through hardcoded bar padding.

## Dependency/API deprecation migration

- confirm actual deprecation in installed version and replacement semantics;
- read migration guide/release notes/source;
- do not replace stable API with alpha experimental API unless requested;
- preserve error/lifecycle/performance/accessibility behavior;
- account for R8/proguard and binary/source compatibility;
- remove old dependency only after transitive/generated/test usages checked.

Examples requiring version checks: Accompanist pager/swipe refresh/system UI -> platform/Compose alternatives; Modifier APIs; Coil state APIs; Paging collection APIs; Material/Wear components; Compose Styles; AppFunctions; billing.

## Refactor safety

For architecture refactors:

- separate move/rename from behavior change when possible;
- preserve public APIs via temporary adapters/deprecations if consumers exist;
- use IDE/compiler references, not regex-only mass replacement;
- avoid package churn and formatting noise;
- verify DI/navigation/serialization/R8 reflection after moves;
- transactionally migrate persisted identifiers/class names if they are serialized;
- remove adapter only when callers/tests are migrated.

## Verification matrix

```text
Compile affected source sets/modules
Unit/coroutine tests
UI/screenshot parity
Accessibility and large text
Deep link/back stack/process recreation
Room schema migration/user data
Background work and cancellation
Release/minified/R8/serialization
Performance baseline if technology affects startup/render/build
Rollback/interop path removed only after success
```

## Anti-patterns

- greenfield template pasted into existing app;
- XML + Compose + ViewModel + navigation + DI migrated in one opaque patch;
- baseline/goldens changed after migration without inspecting differences;
- ComposeView not disposed with Fragment view lifecycle;
- LiveData event converted to sticky StateFlow;
- Rx operator renamed without hot/backpressure tests;
- CameraX analyzer forgets `ImageProxy.close()`;
- forced Navigation 3/AGP 9/compileSdk/alpha Styles;
- destructive Room migration during upgrade;
- obsolete dependency removed before all usages/generated code checked;
- mass reformat/rename obscures behavior change;
- compatibility flag added with no removal plan.


## Deep implementation protocol

### Define parity before modernization

Create a before/after parity matrix for behavior, state restoration, accessibility, analytics, navigation/back behavior, errors, offline behavior, performance, screenshots, public APIs, schema/wire formats, and release behavior. A migration is not successful merely because the new technology compiles.

Split migration dimensions. Do not simultaneously change UI toolkit, architecture, navigation, data source, coroutine semantics, and visual design unless the user explicitly requested a coordinated rewrite and the repository has sufficient tests.

### Stage and rollback

Prefer a seam that allows old and new implementations to coexist briefly behind one contract. Define rollout unit, fallback path, data compatibility, and deletion criteria. Remove old code only after all callers and runtime paths are proven migrated.

### Semantic preservation

For each migration, explicitly map semantics:

- LiveData/Rx to Flow: hot/cold, replay, scheduler, error, completion, backpressure, cancellation;
- XML to Compose: lifecycle owner, saved state, focus/IME, nested scroll, insets, semantics, disposal;
- navigation: route identity, arguments, deep links, result delivery, back stack, process restoration;
- Room/schema: exact row transformation, defaults, indices, foreign keys, downgrade/support window;
- Camera: lifecycle binding, output rotation, backpressure, resource close, capture behavior;
- build/toolchain: source compatibility, generated code, plugins, lint, R8, CI, IDE/JDK constraints.

## AI-generated code hazards

- “modernize everything” patches with large unrelated formatting and no baseline.
- deleting the old path before parity or rollback is proven.
- mechanical API replacement that changes lifecycle, replay, scheduling, backpressure, or error semantics.
- wrapping old APIs in new names while retaining duplicate owners and leaks.
- forced Navigation 3, Hilt, KSP, Compose, coroutines, or multi-module migration not required by task.
- database migration that only bumps version or uses destructive fallback.
- visual rewrite presented as toolkit migration, making regressions impossible to attribute.
- compatibility shims left permanently with two sources of truth.
- deprecation suppression instead of migration, or broad opt-in/suppression hiding unrelated warnings.
- only happy-path screenshot/compile checked; restoration, accessibility, offline, and release omitted.

## Post-change audit

### Before/after proof

- Run the same acceptance scenario on old baseline and new implementation where possible.
- Compare state sequences, emitted effects, database/wire output, back stack, screenshots/semantics, and performance metrics—not just final pixels.
- Exercise old stored data, old deep links, process recreation, background/foreground, rapid actions, cancellation, error/retry, and account state.
- Search for remaining old API usage and confirm each occurrence is intentional.
- Verify no dual collector, duplicate refresh, duplicate analytics, duplicate worker, or double navigation exists during coexistence.
- Build and test every migration stage and the shipping variant.

### Deletion gate

Old code may be removed only when:

```text
[ ] all call sites/routes/DI bindings select the new path;
[ ] persisted and external contracts remain compatible or have tested migration;
[ ] parity tests cover critical behavior and failures;
[ ] rollback is no longer required or is documented elsewhere;
[ ] monitoring shows no material regression where rollout data exists;
[ ] obsolete resources/dependencies/rules are unused and safely removed.
```

### Evidence record

```text
Baseline captured:
Semantics mapped:
Migration stages:
Parity evidence:
Old-path usage search:
Stored-data/restoration checks:
Release/rollback result:
Known parity gaps:
```
