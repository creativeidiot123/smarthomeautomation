---
name: android-compose-ui-effects
description: "Use for Jetpack Compose UI implementation or bugs: recomposition, remember/effects, modifiers, Lazy lists, animation, images, previews, performance, View interop. Not for navigation/persistence ownership."
---

# Compose UI, Effects, Images, and Performance

## Inspect before changing

Identify:

- Compose/compiler/BOM/library versions and experimental opt-ins;
- design system/theme/components;
- Route/Screen and state/event conventions;
- navigation and lifecycle collection helper;
- image loader and caching setup;
- preview/screenshot/UI test conventions;
- View interoperability and composition strategy;
- adaptive/edge-to-edge owner.

Do not migrate XML, Material 2, navigation, image loader, or experimental APIs as a side effect of a screen fix.

## Composition contract

A composable describes UI from inputs and emits actions. It must not:

- call repository/API/DAO/DataStore;
- start non-idempotent work during composition;
- write state while being composed;
- return business values to callers;
- create long-lived service/client/cache objects;
- retain View/Activity/Fragment references;
- rely on recomposition as an execution trigger.

Prefer a route/host that connects ViewModel/platform concerns to a stateless or mostly stateless screen, but do not force extra wrapper composables when the repository has a simpler safe convention.

## State placement

Keep state as low as possible and as high as necessary.

**ViewModel/state owner:** business/screen data, async status, validation, mutations, durable workflow.

**`rememberSaveable`:** UI state worth restoring through configuration/process recreation and representable safely, such as selected tab or draft input where product expects it.

**`remember`:** ephemeral composition-local mechanics such as `SnackbarHostState`, animation object, focus requester, local expansion.

Avoid putting scroll state, FocusRequester, painter, NavController, or callbacks inside ViewModel `UiState`. Avoid keeping repository data only in `remember`.

State hoisting:

- state + event callback for reusable components;
- avoid pass-through wrappers that add no ownership;
- do not pass ViewModel into leaf components;
- pass only state slices children need to reduce coupling/recomposition;
- use stable immutable inputs; do not annotate `@Stable/@Immutable` dishonestly.

## Lifecycle collection

Use `collectAsStateWithLifecycle` for Android state or the repository’s established lifecycle-aware equivalent (Destination: `collectAsStateWithLifecycleCompat()` for state and `CollectLatestWithLifecycle` for effects; raw `collectAsState(` fails `BrutalArchitectureGuardrailTest`). For transient effects, collect in a lifecycle-aware `LaunchedEffect`/host coroutine; do not convert effects to state.

KMP/common UI must use the project’s platform lifecycle bridge; do not import Android lifecycle into commonMain.

## Side effects

### `LaunchedEffect`

Use for composition-owned suspend effects. Keys define restart/cancellation identity.

- key by the actual identity/dependency;
- avoid `Unit`/constant for work that should restart when an ID changes;
- avoid unstable object/lambda keys that restart every recomposition;
- use `rememberUpdatedState` for latest callback/value without restart;
- make repeated execution safe.

### `DisposableEffect`

Register/unregister listener, observer, callback, receiver, or resource with matching keys and deterministic cleanup.

### `SideEffect`

Publish successful composition state to a non-Compose object; no suspend work.

### `produceState`

Bridge external async state only when a ViewModel/repository owner is not more appropriate and cleanup/cancellation is correct.

### `snapshotFlow`

Observe Compose snapshot values from a coroutine. Apply `distinctUntilChanged`/sampling and avoid feeding writes back into the same state loop.

### Derived state

Use `derivedStateOf` when it prevents frequent recomposition for a derived threshold/value; wrap in `remember` with correct dependencies. Do not use it for cheap ordinary derivations merely by habit.

## Modifier contract and order

Public reusable composables normally accept `modifier: Modifier = Modifier` and apply it to the outer meaningful node. Follow project parameter order.

Modifier order changes behavior. Review:

- size/padding versus clickable hit target;
- clip/background/border/ripple;
- semantics before/after clearing/merging;
- graphics transforms and drawing;
- nested scroll and gesture input;
- test tags and semantics;
- insets consumption.

Do not prescribe one universal chain such as “padding→clip→background→clickable”; choose based on desired visual and hit-area semantics.

Avoid `Modifier.composed` for new low-level modifiers when a stable `Modifier.Node` implementation is warranted and supported; do not rewrite existing modifiers without need.

## Lists and scrolling

- stable keys when item identity survives reorder/removal;
- use `contentType` for heterogeneous lists where reuse benefits;
- do not use index as identity for mutable/reorderable data;
- avoid sorting/filtering/grouping and allocations inside `items`/item body;
- avoid `indexOf` per item and concurrent list mutation;
- preserve scroll state through correct owner/key;
- use `LazyColumn` mixed `item/items` rather than nesting same-direction unbounded scroll containers;
- nested scrolling is allowed only with bounded layout and deliberate coordination;
- paginate through Paging/data owner, not manual “near end” mutation races when Paging is already used;
- animations on list changes need stable keys and tested reorder/removal behavior.

## Recomposition and stability

Measure or inspect before optimizing.

- avoid broad parent collection when children need narrow state;
- do not create new callbacks/collections/formatters/requests in hot item scopes unnecessarily;
- use `remember` for expensive object construction tied to stable keys;
- prefer immutable collections or repository’s stable collection type for state;
- avoid redundant state updates/copies when equal;
- lambdas in `UiState` and mutable collections harm reasoning/stability;
- use compiler stability/recomposition tooling when needed rather than guessing;
- strong skipping does not excuse unstable mutation or hidden side effects.

Do not add `@Stable`/`@Immutable` to suppress warnings unless the contract is true for all observable fields and equality behavior.

## Layout and drawing

- choose Row/Column/Box for simple layout; ConstraintLayout/custom Layout only when constraints/measurement justify them;
- avoid intrinsic measurements in large/hot lists without evidence;
- use `drawWithCache` when expensive draw objects depend on size/state and can be cached;
- do not allocate Paint/Path/Shader every frame;
- use density/layout direction correctly;
- keep custom drawing accessible through semantics/alternate content;
- avoid `fillMaxSize` roots that ignore intended parent constraints;
- use Scaffold only when its slots/inset behavior match the screen—not as a universal root.

## Animation

Choose the simplest API:

- `animate*AsState` for one value;
- `updateTransition` for coordinated state values;
- `AnimatedVisibility` for enter/exit;
- `AnimatedContent` for content transitions with correct keys;
- `Animatable` for imperative interruption/physics;
- infinite transitions only for bounded visible decorative/progress behavior.

Rules:

- animation state derives from UI state, not hidden competing booleans;
- user input interrupts/redirects animation;
- avoid launching a new animation on every recomposition;
- keep expensive blur/shadow/layer effects measured;
- preserve content semantics during transitions;
- respect reduced motion when product/accessibility support exists;
- do not block mutation/input until decorative animation completes unless workflow truly requires it;
- test rapid state changes and disposal.

## Images and Coil

Preserve the existing image library/version. For Coil 3-like APIs:

- `AsyncImage` is generally preferred because layout constraints inform requested size;
- `rememberAsyncImagePainter` requires an explicit size resolver/request size when original-size loading would waste memory;
- `SubcomposeAsyncImage`/subcomposition state slots are expensive in large lazy lists; prefer normal placeholders/error callbacks unless custom composable state is necessary;
- include loading, error, fallback, content scale, clipping, and meaningful/decorative `contentDescription`;
- stable data/model/request avoids refetch/restart;
- do not instantiate ImageLoader per composable;
- size thumbnails to display needs; avoid original multi-megapixel loads;
- sensitive/authenticated images need cache/header/privacy policy;
- KMP uses platform context/engine through project abstraction;
- verify required network module and R8/decoder support for the installed version.

Do not hardcode a Coil API from A if the project version differs. Check actual signatures/source/docs.

## CompositionLocal

Use for tree-scoped ambient UI dependencies such as theme tokens, density, layout direction, or stable UI services where explicit threading is impractical. Do not use it as a service locator for repositories, mutable business state, navigation, or arbitrary dependencies.

Choose static versus dynamic local based on whether changes should recompose readers. Provide safe defaults or fail clearly for required values.

## View interoperability

**Compose in Fragment/View:**

- set a composition strategy tied to the View tree lifecycle (commonly `DisposeOnViewTreeLifecycleDestroyed`);
- do not retain ComposeView/binding beyond `onDestroyView`;
- pass lifecycle-aware state and callbacks;
- preserve accessibility/focus/insets.

**View in Compose (`AndroidView`):**

- create in `factory`, update idempotently in `update`;
- unregister/release in `DisposableEffect` or API-specific release hook;
- do not recreate expensive View for ordinary state updates;
- avoid View callbacks that write state repeatedly during update;
- respect measurement and save state.

Use specialized Compose-native camera/viewfinder APIs when the installed library supports them and behavior is verified; interop remains acceptable when migration is not requested.

## Preview and testability

- screen/content composables accept state and callbacks;
- previews use deterministic sample data and theme;
- avoid real DI/network/database in previews;
- preview loading/content/empty/error, dark mode, large font, and relevant window sizes;
- semantics-based UI tests target user behavior;
- add test tags only when stable semantics cannot identify the element;
- screenshot tests use existing framework and controlled font/density/time/animation.

## Production crash checks

Review for:

- `remember` missing identity/configuration key;
- stale lambda captured by long-running effect;
- list index/`indexOf` mismatch after mutation;
- concurrent mutable list iteration;
- state write during composition;
- navigation/effect re-triggered on recomposition;
- missing listener/resource cleanup;
- unbounded image size;
- nested infinite constraints;
- `LazyColumn` key collision;
- unsafe cast/`!!` from transient state;
- View composition not disposed.

## Anti-patterns

- `collectAsState()` for Android lifecycle-sensitive state when lifecycle helper exists;
- effect stream via `collectAsStateWithLifecycle`;
- repository call in `LaunchedEffect` with unstable key and no owner guard;
- ViewModel passed to every child;
- screen data stored only in `remember`;
- backwards state write during composition;
- expensive sort/filter/request in item body;
- no key for reorderable items;
- subcomposition-heavy images in long list without need;
- hardcoded design values bypassing project tokens;
- CompositionLocal as global service locator;
- stateful modifier/order copied without behavior review;
- false `@Stable` annotation;
- replacing View/XML with Compose outside requested migration.


## Deep implementation protocol

### Composition ownership audit

Classify every value read by a composable as immutable parameter, lifecycle-collected screen state, remembered UI-local state, derived state, or ambient dependency. No value should be both remembered locally and independently owned by ViewModel/source truth unless there is an explicit draft/commit protocol.

### Effect contract

For every effect, document key identity, start condition, cancellation condition, captured values, cleanup, restart cost, and whether the work belongs in UI at all. Use `rememberUpdatedState` for changing callbacks captured by a long-lived effect; do not use it to hide a key that should restart work.

### Recomposition cost model

Inspect state read location, invalidation scope, list item identity, allocation/transform work, image request identity, layout/draw cost, and animation clock. Optimize only after observing a user-relevant cost.

## AI-generated code hazards

- repository/API/DAO call directly in composable or effect because it is “only initialization.”
- mutation launched from `LaunchedEffect` with unstable/unit key and replayed on re-entry.
- `remember` used as persistent/source-of-truth storage or to mask expensive incorrect ownership.
- `derivedStateOf` around cheap values or with unstable dependencies, adding overhead/bugs.
- `snapshotFlow` reads values outside its block or collects forever without lifecycle need.
- list items keyed by index while insertion/reorder/stateful content exists.
- unstable lambdas/models blamed without measurement; blanket `@Stable/@Immutable` lies about mutability.
- expensive mapping/sorting/image request construction in composition or every row.
- `CompositionLocal` used as service locator for repositories/navigation/business dependencies.
- nested scrollables/unbounded constraints and modifier order copied without semantic review.
- semantics duplicated or click behavior attached to both parent and child.
- preview-only fake path diverges from real state contract.
- tests assert node implementation details and miss lifecycle duplicate effects.

## Post-change audit

### Compose adversary matrix

- recompose repeatedly without new business mutation/network request;
- navigate away/back and background/foreground;
- rotate/recreate and restore saved UI state;
- rapidly change effect keys/input;
- reorder/insert/remove list items while preserving correct item state;
- show loading/content/empty/error/refresh/offline states;
- open IME, resize window, apply large font and RTL;
- use accessibility semantics and alternate input;
- fail image/network/resource and cancel during disposal.

### Structural/performance checks

- Inspect state reads and effect keys in the final diff.
- Verify lifecycle-aware state/effect collection and exact cleanup.
- Search for business work in composables, unstable per-frame/per-row allocations, index keys, and new CompositionLocals.
- Use recomposition/layout tracing or benchmarks only when there is measured risk; compare release-like frame/user metrics before claiming improvement.
- Confirm UI tests detect duplicate action/effect and state restoration, not only final text.

### Evidence record

```text
State owners and UI-local drafts:
Effect keys/start/cancel/cleanup:
Recomposition/business-duplication result:
List identity/restoration result:
Semantics/adaptive/IME result:
Measured performance evidence:
Preview/test parity:
Unverified Compose lifecycle case:
```
