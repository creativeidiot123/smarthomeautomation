# Destination good/bad pairs

Use these as templates. Each "bad" shape is one that AI agents commonly write; each "good" shape uses what this repo already has. If a pair does not fit your case, follow the owning code, not this file.

## Errors

Bad: swallows failure and hides cancellation.

```kotlin
val rule = runCatching { dao.getRule(pkg) }.getOrNull()
```

Good: cancellation is rethrown; failure is visible and logged with context.

```kotlin
val rule = runCatchingNonCancellation { dao.getRule(pkg) }
    .onFailure { FocusLog.e(FocusEventId.EXAMPLE, "rule read failed pkg=$pkg", it) }
    .getOrElse { return RuleLoad.Failed }
```

## Coroutine scope

Bad: a scope nobody cancels.

```kotlin
class Refresher { fun start() = CoroutineScope(Dispatchers.IO).launch { refreshAll() } }
```

Good: work belongs to an owner that already exists: `viewModelScope` for screen work, the composition-root `applicationScope` for process work, or WorkManager for work that must survive the process.

```kotlin
fun onRefreshClicked() { viewModelScope.launch { repository.refreshAll() } }
```

## Nulls

Bad (also fails `BrutalArchitectureGuardrailTest`):

```kotlin
val label = packageManager.getApplicationInfo(pkg, 0)!!.loadLabel(packageManager)
```

Good: handle the missing case explicitly and say why.

```kotlin
val info = runCatchingNonCancellation { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
    ?: return AppLabel.Unavailable.also { FocusLog.w(FocusEventId.EXAMPLE, "label skipped pkg=$pkg reason=not_found") }
```

## Timing

Bad: a delay pretending to be synchronization.

```kotlin
policyApplyGateway.requestApply(trigger)
delay(500) // wait for policy to apply
refreshUi()
```

Good: wait for the owner's completion signal, or observe the state the owner publishes. If the owner has no completion signal, that is the bug to fix.

## Exposed state

Bad:

```kotlin
val uiState = MutableStateFlow(AppRulesUiState())
```

Good:

```kotlin
private val _uiState = MutableStateFlow(AppRulesUiState())
val uiState: StateFlow<AppRulesUiState> = _uiState.asStateFlow()
```

## Modes

Bad: Booleans that can contradict each other.

```kotlin
data class State(val isLoading: Boolean, val isError: Boolean, val isEmpty: Boolean, val items: List<Item>)
```

Good: one sealed mode.

```kotlin
sealed interface Content {
    data object Loading : Content
    data object Empty : Content
    data class Data(val items: List<Item>) : Content
    data class Error(val message: UiText) : Content
}
```

## Ceremony

Bad: a class that only forwards one call.

```kotlin
class GetRulesUseCase(private val repo: AppRulesRepository) { operator fun invoke() = repo.observeRules() }
```

Good: call `repo.observeRules()` directly. Add a new class only when it owns real policy, reuse, a transaction, or a test seam, and state that reason in one line.

## Dispatchers

Bad: `withContext(Dispatchers.IO) { dao.getAll() }` around a suspend Room DAO call. Room is already main-safe.

Good: call suspend DAO functions directly. Wrap only genuinely blocking calls (PackageManager queries, file I/O, `DevicePolicyManager` bulk work) in `withContext(Dispatchers.IO)`, at the boundary that makes the call.

## Compose loading

Bad: the UI decides when data loads.

```kotlin
LaunchedEffect(Unit) { viewModel.load() }
```

Good: the ViewModel owns loading (in `init`, or with `stateIn` + `onStart`), and the UI collects with `collectAsStateWithLifecycleCompat()`.

## New classes

Bad: a new `XyzManager`/`XyzHelper`/`XyzCoordinator` "to keep it clean".

Good: add the function to the existing owner, or add a top-level function in the owner's package. If the owner file is already over 500 lines, put the new cohesive logic in a new file beside it; don't create a new layer.

## Logging

Bad: `FocusLog.d(FocusEventId.EXAMPLE, "state=$state")` dumps a whole object, and can leak visited domains, QR/NFC payloads, or location.

Good: `FocusLog.w(FocusEventId.EXAMPLE, "apply failed op=$op type=${e::class.simpleName} pkgCount=${pkgs.size}")` records the operation, the reason, and counts or IDs.

## Workers

Bad: `class SyncWorker(ctx: Context, params: WorkerParameters, private val repo: Repo)` crashes with the default WorkerFactory.

Good: `(Context, WorkerParameters)` only. Resolve dependencies through `AppServices.destinationApplicationOrNull(applicationContext)` and check `awaitUnlockedStartupReady()`, like `PolicyReconcileWorker`.

## Tests

Bad: assert that a mock was called, or that a flag you just set is set.

Good: perform the action through the real owner, assert the observable result (state, Room row, applied policy), and confirm the test fails with the fix reverted.
