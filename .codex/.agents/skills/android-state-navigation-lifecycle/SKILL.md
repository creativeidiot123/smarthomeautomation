---
name: android-state-navigation-lifecycle
description: "Use for Android UI state/navigation/lifecycle bugs or design: ViewModel state, effects, SavedStateHandle/rememberSaveable, Activity Result/permission/OAuth/MediaProjection launchers, tabs/back stack, deep links, recreation/process restoration, screen orchestration."
---

# State, Navigation, and Lifecycle

## Establish the contract

Before code, identify:

- screen truth and ViewModel state owner;
- which values survive recomposition, configuration change, navigation away/back, and process death;
- which outputs are durable outcomes versus transient presentation effects;
- navigation host and route argument contract;
- duplicate-tap and stale-operation policy;
- permission/result launcher owner;
- restoration source (`SavedStateHandle`, Room, server, DataStore, route arguments).

## UiState

Prefer one immutable public state per coherent screen/flow. Model mutually exclusive content with a sealed type when booleans would create impossible combinations.

```kotlin
data class DetailUiState(
    val content: DetailContent = DetailContent.Loading,
    val isRefreshing: Boolean = false,
    val save: SaveState = SaveState.Idle,
)

sealed interface DetailContent {
    data object Loading : DetailContent
    data object Empty : DetailContent
    data class Data(val item: ItemUi) : DetailContent
    data class Failed(val error: UiError, val staleItem: ItemUi? = null) : DetailContent
}
```

Guidelines:

- private mutable/public read-only state;
- atomic `update` when derived from prior state;
- avoid duplicated derived fields that can drift;
- do not store lambdas, NavController, Context, Views, painters, or lifecycle objects in state;
- do not put large mutable objects or whole repositories in `SavedStateHandle`;
- keep canonical values and derive localized/formatted text at the presentation edge unless the project’s UI model already owns formatting;
- represent retry/offline/stale/permission/empty states explicitly where they affect behavior.

## Actions

Use explicit action methods for simple screens or a sealed action type for screens with many interactions. Both are valid; follow the repository.

Actions should describe user/system intent, not UI widget implementation:

```kotlin
sealed interface EditorAction {
    data class TitleChanged(val value: String) : EditorAction
    data object SaveClicked : EditorAction
    data object RetryClicked : EditorAction
}
```

Validate at the owner. The UI may provide immediate visual constraints, but business validation and mutation eligibility belong in ViewModel/domain/repository as appropriate.

## Durable outcome versus transient effect

Use state/truth for outcomes that must survive recreation:

- purchase/entitlement completed;
- item saved/deleted;
- onboarding step completed;
- authentication changed;
- navigation destination implied by durable workflow state.

Use a transient effect stream for presentation that may be reconstructed or safely missed according to an explicit policy:

- snackbar/toast;
- haptic/sound;
- focus/scroll request;
- opening a system picker;
- navigation request when the underlying state is still authoritative and the action is idempotent.

No `Channel` or `SharedFlow` guarantees exactly-once across process death. A Channel can queue for a later collector within the same process, but events may still be lost on process termination or mishandled by multiple collectors. Critical workflows use persisted/replayable state plus idempotent consumption.

Avoid sticky Boolean flags such as `navigate = true` or `showMessage = true` unless the repository has a proven consumed-event protocol with restoration tests.

## Effect collection in Compose

State:

```kotlin
val state by viewModel.state.collectAsStateWithLifecycle()
```

Effects should be collected in a lifecycle-aware coroutine. Use the project’s helper if present (HomeDJ has none and no navigation library; tabs are `rememberSaveable` state and truth comes from ViewModels/services). The underlying safe shape is:

```kotlin
val lifecycleOwner = LocalLifecycleOwner.current
val currentNavigate by rememberUpdatedState(onNavigate)

LaunchedEffect(viewModel, lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is Effect.Navigate -> currentNavigate(effect.destination)
                is Effect.Message -> snackbarHostState.showSnackbar(effect.text)
            }
        }
    }
}
```

Do not use `collectAsStateWithLifecycle` for one-time effects; it converts the latest emission into sticky Compose state. Do not collect the same effect stream in multiple owners unless broadcast semantics are intentional.

## Activity and Fragment

- Keep Activity/Fragment thin: hosting, lifecycle, system APIs, navigation edge.
- Fragment View binding exists only from `onCreateView` through `onDestroyView`; clear references.
- Collect UI flows with `viewLifecycleOwner.repeatOnLifecycle`, not Fragment lifecycle, when updating views.
- Do not retain Fragment/Activity/View references in ViewModel, repository, singleton, callback, or long-lived coroutine.
- Register Activity Result contracts before started state according to API requirements; launch only from UI/system edge.
- Handle returned result, cancellation, missing handler, revoked permission, and process recreation.
- Do not launch business work blindly in every `onResume`/`onStart`; define refresh idempotency and de-duplication.

## SavedStateHandle and restoration

Use it for small route/restoration inputs and lightweight user-entered state, not as a database or object graph.

- parse and validate arguments once;
- distinguish missing, malformed, and unauthorized IDs;
- re-load authoritative data from repository;
- save compact primitives/IDs or supported serializable types;
- do not persist secrets unless the storage and threat model explicitly permit it;
- test process recreation for critical forms/workflows.

## Navigation ownership

- Host/Route/Activity/Fragment executes navigation.
- ViewModel exposes state/effect/intent independent of navigation implementation.
- Repository/use case/worker never navigates.
- Pass stable IDs/route primitives, not whole objects.
- Destination revalidates ID, auth, authorization, and availability.
- Handle malformed/deleted/not-found targets with a safe state.
- Preserve back-stack semantics and predictive back.
- Prevent duplicate destinations on repeated taps through UI disablement and navigation options/guarding.
- For multiple back stacks, save/restore state according to the selected navigation library and test top-level switching.

### Navigation results

Choose semantics:

- durable result: persist/update repository truth; destination observes it;
- back-stack-local result: use the navigation library’s saved-state/result API with a unique key and consume/clear once;
- transient effect: use effect stream only when loss is acceptable or reconstructable.

Do not use global mutable event buses.

## Deep links and App Links

Treat all incoming routes as untrusted input.

- verify scheme/host/path/query and expected source where applicable;
- reject unexpected extras and oversized payloads;
- never infer authorization from a deep link;
- route to an authentication gate when needed, then resume safely;
- avoid open redirects;
- normalize IDs and canonical URLs;
- test cold start, warm start, authenticated/unauthenticated, malformed, duplicate, and back behavior.

## Permissions

UI edge owns the launcher; state owner owns rationale and result behavior.

Model:

- not requested;
- rationale needed;
- granted;
- denied;
- permanently denied/settings route;
- unavailable hardware/API;
- degraded feature mode.

Do not request at startup without immediate feature need. Do not loop prompts. Re-check permission when returning from settings and before sensitive operations.

## Common failures

| Failure | Correction |
|---|---|
| ViewModel holds NavController | emit intent/state; host navigates |
| whole Parcelable/domain object in route | pass ID and reload |
| event collected as state | lifecycle-aware `collect` |
| Channel described as process-safe exactly-once | persist critical outcome and consume idempotently |
| Fragment collects with its own lifecycle while view is destroyed | use `viewLifecycleOwner` |
| save button launches parallel writes | disable + ViewModel/repository single-flight/idempotency |
| refresh in every composition/lifecycle callback | explicit guarded refresh owner |
| permission result handled only in Composable local Boolean | ViewModel state + launcher at UI edge |
| deep link opens private screen directly | authenticate/authorize at destination |

## Verification

Test state transitions, duplicate actions, cancellation, stale result suppression, configuration/process recreation where material, back-stack behavior, malformed arguments, deep links, permission denial, and result cancellation.


## Deep implementation protocol

### Classify every output by durability

| Output | Correct owner | Survives recreation? | Survives process death? | Consumption rule |
|---|---|---:|---:|---|
| Rendered content/status | `UiState` or data truth | yes | when restored/reloaded | render idempotently |
| Navigation request that may be lost safely | transient effect | only while collected | no | lifecycle-aware collection |
| Critical completion/navigation | persisted or saved durable state | yes | if persisted | acknowledge/consume by stable ID |
| Toast/snackbar/haptic | transient presentation effect | no guarantee | no | best effort only |
| Form draft | `SavedStateHandle`, ViewModel, or persistence by product requirement | yes | chosen explicitly | restore without replaying mutation |

Do not choose `SharedFlow`, `Channel`, state flag, or saved state before deciding the durability contract.

### Model a real state machine

For non-trivial screens, enumerate legal transitions. Confirm that loading, content, empty, blocking error, refreshing, submitting, and terminal outcomes cannot form contradictory combinations. Separate independent axes only when they can genuinely coexist, such as content plus non-blocking refresh.

For every action, define:

```text
Allowed source states:
Immediate state change:
Async owner:
Duplicate policy:
Cancellation policy:
Success transition:
Failure transition:
Restoration behavior:
Navigation/effect behavior:
```

### Navigation boundary

The UI/host resolves routes and performs navigation. The ViewModel emits a typed destination intent or durable outcome, never route strings coupled to a specific navigation library. Destination authorization is re-evaluated from current truth, not trusted route arguments.

## AI-generated code hazards

- `MutableStateFlow` or mutable LiveData exposed publicly because it is convenient in Compose.
- Boolean `hasNavigated`, `showToast`, or `paymentDone` flags reset by the UI, creating replay and race bugs.
- `Channel` described as exactly-once across recreation or process death.
- `collectAsState()` without lifecycle awareness on Android.
- `LaunchedEffect(Unit)` collecting a ViewModel forever without lifecycle gating when the route remains composed off-screen.
- navigation inside repository/use case/ViewModel through `NavController`, context, or callback retained beyond the UI lifecycle.
- entire objects serialized into route arguments instead of stable IDs.
- route arguments used as authorization or as the only source of current data.
- multiple parallel state holders for the same form: `remember`, `SavedStateHandle`, ViewModel state, and text field internal state.
- state reset in `init` or on every collector, erasing restored state.
- permission request automatically relaunched after denial or recreation.
- duplicate navigation caused by rapid clicks, multiple collectors, or effect replay.

## Post-change audit

### Lifecycle matrix

Exercise or test the applicable rows:

| Scenario | Expected state | Expected effect/navigation | Forbidden behavior |
|---|---|---|---|
| configuration change | content/form retained or deterministically reloaded | no duplicate effect | duplicate request/navigation |
| background then foreground | current truth rendered | only still-valid transient work | unconditional refresh/replay |
| process recreation | route and durable state recover by contract | critical outcome resumes idempotently | reliance on in-memory event |
| double tap / rapid action | selected duplicate policy | at most intended destinations/mutations | stacked routes or duplicate writes |
| two collectors | same state | one policy outcome | duplicated refresh or mutation |
| deep link while signed out | safe gate state | auth flow or rejection | privileged destination trusted |
| permission denial/permanent denial | degraded state | rationale/settings action as appropriate | request loop |

### State audit

- Search all writes to affected state and prove atomicity when based on prior value.
- Verify no UI-local copy can diverge from ViewModel/source truth.
- Confirm effect collectors use stable keys, current callbacks where required, and lifecycle cleanup.
- Confirm navigation result keys and saved-state entries are cleared/acknowledged idempotently.
- Verify back-stack behavior, single-top/pop policy, and deep-link reconstruction.

### Evidence record

```text
State machine/transition tested:
Recreation behavior:
Process-restoration assumption:
Duplicate-action result:
Multiple-collector result:
Deep-link/auth result:
Permission result:
Checks not run:
```
