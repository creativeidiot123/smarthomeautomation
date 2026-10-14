---
name: android-implementation-patterns
description: "Use when implementing an already-designed Android/Kotlin pattern: immutable state, lifecycle effects, latest-wins, single-flight, offline transactions, callbackFlow, WorkManager, safe errors. Owners first."
---

# Reusable Android Patterns

These are adaptation templates, not copy-paste mandates. Match the repository's package structure, result/error types, DI, lifecycle APIs, dependency versions, and test utilities. Delete unused layers rather than preserving ceremony.

## 1. Screen contract and immutable state

```kotlin
@JvmInline
value class ItemId(val value: String)

data class ExampleUiState(
    val content: Content = Content.Loading,
    val query: String = "",
) {
    sealed interface Content {
        data object Loading : Content
        data class Data(val items: List<ItemUi>) : Content
        data object Empty : Content
        data class Error(val message: UiText) : Content
    }
}

sealed interface ExampleAction {
    data class QueryChanged(val value: String) : ExampleAction
    data object Retry : ExampleAction
}

```

Use one sealed mode when loading/data/empty/error are mutually exclusive. Keep durable outcomes in `UiState` or the source of truth; effects are only transient presentation requests.

## 2. ViewModel state and latest-wins refresh

```kotlin
internal class ExampleViewModel(
    private val repository: ExampleRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ExampleUiState())
    val state: StateFlow<ExampleUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun onAction(action: ExampleAction) {
        when (action) {
            is ExampleAction.QueryChanged -> _state.update { it.copy(query = action.value) }
            ExampleAction.Retry -> refresh()
        }
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(content = ExampleUiState.Content.Loading) }
            val result = repository.loadItems() // must rethrow CancellationException
            ensureActive() // a cancelled older refresh stops here; no second guard needed
            _state.update { current ->
                current.copy(
                    content = result.fold(
                        onSuccess = { items ->
                            if (items.isEmpty()) ExampleUiState.Content.Empty
                            else ExampleUiState.Content.Data(items)
                        },
                        onFailure = { error ->
                            FocusLog.w(FocusEventId.EXAMPLE, "refresh failed type=${error::class.simpleName}")
                            ExampleUiState.Content.Error(error.toUiText())
                        },
                    )
                )
            }
        }
    }
}
```

One guard is enough: `cancel()` + `ensureActive()` stops a stale coroutine from writing state. Add an operation generation counter only when the source is a callback API that keeps delivering after cancellation (see references). The repository produces `Result` with `runCatchingNonCancellation`, so cancellation is never turned into an error. `FocusEventId.EXAMPLE` stands for a real constant from `FocusEventId`. Simple destination selection uses a host navigation callback directly from the UI action instead of an effect stream.

## 3. Compose route: lifecycle-aware state and direct navigation

```kotlin
@Composable
fun ExampleRoute(
    viewModel: ExampleViewModel,
    onOpenItem: (ItemId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycleCompat()

    ExampleScreen(
        state = state,
        onQueryChanged = { value -> viewModel.onAction(ExampleAction.QueryChanged(value)) },
        onRetry = { viewModel.onAction(ExampleAction.Retry) },
        onItemClick = onOpenItem,
    )
}
```

Pass callbacks straight through. `rememberUpdatedState` is only for a lambda read inside a long-lived `LaunchedEffect`/`DisposableEffect` that must not restart; wrapping a callback you pass to a child does nothing. The screen remains a stateless renderer except for UI mechanics such as focus, scroll, dialog expansion, or snackbar host state.

## 4. Optional best-effort presentation effect

Add an effect stream only when the screen has a real transient presentation request that is safe to miss, such as a snackbar or haptic. Do not add it pre-emptively and do not route a critical workflow outcome through it.

```kotlin
sealed interface ExampleEffect {
    data class ShowMessage(val message: UiText) : ExampleEffect
}

private val _effects = MutableSharedFlow<ExampleEffect>(
    replay = 0,
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
val effects: SharedFlow<ExampleEffect> = _effects.asSharedFlow()

fun onHelpClicked() {
    _effects.tryEmit(
        ExampleEffect.ShowMessage(UiText.Resource(R.string.example_help))
    )
}
```

This stream is intentionally lossy. It has no process-death or exactly-once guarantee and is unsuitable for payment, save completion, entitlement, destructive confirmation, or required navigation.

Collect it only while the relevant UI lifecycle is active, using the project helper:

```kotlin
val context = LocalContext.current
CollectLatestWithLifecycle(viewModel.effects) { effect ->
    when (effect) {
        is ExampleEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message.asString(context))
    }
}
```

## 5. Latest-wins search with Flow

```kotlin
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class) // debounce + flatMapLatest
internal class SearchViewModel(
    private val repository: SearchRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")

    val state: StateFlow<SearchUiState> = query
        .map(String::trim)
        .debounce(300)
        .distinctUntilChanged()
        .flatMapLatest { normalized ->
            if (normalized.isBlank()) {
                flowOf(SearchUiState.Idle)
            } else {
                repository.search(normalized)
                    .map<List<SearchItem>, SearchUiState> { items ->
                        if (items.isEmpty()) SearchUiState.Empty
                        else SearchUiState.Results(items)
                    }
                    .onStart { emit(SearchUiState.Loading) }
                    .catch { throwable ->
                        if (throwable is CancellationException) throw throwable
                        emit(SearchUiState.Error)
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SearchUiState.Idle,
        )

    fun onQueryChanged(value: String) {
        query.value = value
    }
}
```

`flatMapLatest` is appropriate for cancelable reads where only the newest result matters. Do not use latest-wins for non-idempotent writes unless the product explicitly supports cancellation/compensation.

## 6. Offline-first local truth and atomic refresh

Destination has no REST API; use this only for a future remote source. The Room transaction part applies to any multi-write local refresh.

```kotlin
class ItemRepository(
    private val database: AppDatabase,
    private val itemDao: ItemDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val api: ItemApi,
    private val clock: Clock,
) {
    fun observeItems(): Flow<List<Item>> =
        itemDao.observeAll().map { entities -> entities.map(ItemEntity::toDomain) }

    suspend fun refresh(): Result<Unit> = runCatchingNonCancellation {
        val response = api.getItems()
        database.withTransaction {
            itemDao.replaceAll(response.items.map(ItemDto::toEntity))
            syncMetadataDao.upsert(
                SyncMetadataEntity(
                    key = "items",
                    refreshedAt = clock.instant(),
                    version = response.version,
                )
            )
        }
    }
}
```

Use a DAO `@Transaction` method instead when it naturally owns the entire atomic operation. Do not inject a DAO and assume it has `withTransaction`. Define whether replace deletes local-only/pending rows before using it.

## 7. First-wins per ID (single-flight mutation)

```kotlin
// ViewModel-owned. viewModelScope runs on Main, so this set is thread-confined: no Mutex, no extra class.
private val inFlight = mutableSetOf<ItemId>()

fun onFavoriteClicked(id: ItemId, favorite: Boolean) {
    if (!inFlight.add(id)) return // already running for this id
    viewModelScope.launch {
        try {
            repository.setFavorite(id, favorite)
        } finally {
            inFlight.remove(id) // not suspending, so it runs even after cancellation
        }
    }
}
```

Never put a suspending call (for example `mutex.withLock`) in `finally` to release a gate: in a cancelled coroutine it can throw before the release runs and leave the ID stuck forever. If the gate must hold across screens, processes, or retries, it belongs at the durable owner (Room unique constraint/transaction, unique work), not in a ViewModel or `Mutex`. This is first-wins only; it does not make a write idempotent.

## 8. Callback API to Flow

```kotlin
fun LocationSource.locations(): Flow<Location> = callbackFlow {
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            trySend(location).onFailure {
                // Optional bounded diagnostic; never log sensitive coordinates.
            }
        }
    }

    client.register(listener)
    awaitClose { client.unregister(listener) }
}.conflate()
```

Choose buffering deliberately. `conflate` is valid only when intermediate values may be dropped. Registration failure should close the flow with a mapped error. Always unregister in `awaitClose`; avoid retaining lifecycle owners in the callback source.

## 9. Durable idempotent WorkManager work

Destination uses WorkManager's default factory, so a worker takes only `(Context, WorkerParameters)` and resolves dependencies from the composition root. A constructor-injected dependency crashes at runtime. Copy the shape of `PolicyReconcileWorker`:

```kotlin
internal class ExampleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = AppServices.destinationApplicationOrNull(applicationContext)
            ?: return Result.retry()
        if (!app.awaitUnlockedStartupReady()) return Result.retry()
        val id = inputData.getString(KEY_ID) ?: return Result.failure()

        return when (app.exampleRepository.process(id)) { // must be idempotent: retries repeat it
            Outcome.Done, Outcome.AlreadyDone -> Result.success()
            Outcome.Transient -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            Outcome.Permanent -> Result.failure()
        }
    }

    companion object {
        const val KEY_ID = "id"
        private const val MAX_ATTEMPTS = 5
    }
}
```

Enqueue with `enqueueUniqueWork` keyed by the entity, pass stable IDs rather than payloads, define constraints/backoff, and log the terminal failure with `FocusLog`. Worker retry can repeat after partial completion.

## 10. Cancellation-safe error mapping

Use the project helper instead of writing a new wrapper:

```kotlin
suspend fun save(rule: AppRule): Result<Unit> =
    runCatchingNonCancellation { dao.upsert(rule.toEntity()) }
        .onFailure { error ->
            FocusLog.e(FocusEventId.EXAMPLE, "save failed pkg=${rule.packageName} type=${error::class.simpleName}", error)
        }
```

`runCatchingNonCancellation` rethrows `CancellationException`. Map to a typed failure only where callers branch on it (for example `SQLiteException` -> storage, `SecurityException` -> missing platform permission). Let programming errors surface. Do not return an empty list, `null`, or success for an unknown exception merely to keep the UI running.

## 11. Focused coroutine test

```kotlin
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

class SearchViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule() // reuse an existing rule if the repo has one

    @Test
    fun newestQueryWins() = runTest { // uses Main's test scheduler automatically
        val repository = FakeSearchRepository()
        val viewModel = SearchViewModel(repository)
        // stateIn(WhileSubscribed) never starts upstream without a subscriber
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }

        viewModel.onQueryChanged("old")
        advanceTimeBy(350)
        viewModel.onQueryChanged("new")
        advanceUntilIdle()

        assertEquals(SearchUiState.Results(repository.resultsFor("new")), viewModel.state.value)
        assertTrue(repository.wasCancelled("old"))
    }
}
```

`viewModelScope` runs on `Dispatchers.Main`, so tests must replace Main with a test dispatcher. The repo uses JUnit4 asserts (`org.junit.Assert`), not Truth/AssertJ. Assert externally observable behavior and cancellation/duplicate semantics, not private implementation details, and confirm the test fails with the fix reverted.

## Adaptation checklist

```text
[ ] Owners and concurrency semantics were named first.
[ ] Template APIs exist in installed dependency versions.
[ ] No new wrapper/layer/dependency was added merely for the pattern.
[ ] Cancellation, recreation, duplicate actions, and offline/stale behavior are defined.
[ ] Critical outcomes do not rely on a transient event stream.
[ ] Room operations use a real database/DAO transaction owner.
[ ] Errors are mapped at the owning boundary without swallowing cancellation.
[ ] Tests prove the risk that motivated the pattern.
```


## Pattern selection discipline

Do not paste a pattern because its shape resembles the task. Select it by invariant:

| Required invariant | Candidate pattern | Do not use when |
|---|---|---|
| latest input owns visible result | latest-wins Flow/job + generation guard if boundary ignores cancellation | operation is a non-idempotent write |
| at most one mutation in flight | mutex/in-flight state/single-flight gate | operations should queue independently |
| ordered durable mutation | Room outbox + unique worker/idempotency | immediate UI-only ephemeral action |
| local database is visible truth | observe DAO + atomic remote refresh | product explicitly requires online-only response |
| transient presentation request | lifecycle-aware effect stream | outcome must survive process death |
| critical once-per-ID outcome | durable state/record + idempotent acknowledgement | best-effort snackbar/haptic |
| callback resource as stream | `callbackFlow` + exact unregister/close | API is naturally one-shot suspendable |
| process-surviving work | unique idempotent WorkManager | work is immediate and screen-bound |

### Adaptation proof

Before using a template, replace every placeholder and verify:

```text
Owner scope:
Truth source:
Duplicate policy:
Cancellation policy:
Error mapping:
Restoration/process behavior:
Account key:
Transaction/idempotency boundary:
Dependency/dispatcher/clock seams actually needed:
Test oracle:
```

Do not preserve template types, layers, wrappers, or names that the repository does not need.

## Destination good/bad pairs

Read [references/destination-good-bad.md](references/destination-good-bad.md) before writing new Kotlin in this repo. It lists the common wrong shapes and the repo-correct replacement.

## Additional robust patterns

Read [references/advanced-concurrency-patterns.md](references/advanced-concurrency-patterns.md) when work needs durable outcome acknowledgement, cancellation-advisory generation guards, or account-scoped single-flight. Do not load those patterns for ordinary state/read/write work.

## AI-generated code hazards

- copying all template layers and names into a repository with different owners.
- exposing mutable fields from the template for test convenience.
- changing a Flow pattern to `launch { collect }` per action, accumulating collectors.
- using generation guard as a substitute for cancelling expensive work or making writes idempotent.
- using `Mutex` in one instance while DI creates multiple instances, so “single flight” is not global to the intended key.
- using in-memory acknowledgement for outcomes that must survive process death.
- worker unique name not keyed by account/entity, causing unrelated work replacement or duplication.
- transaction sample updated to include network calls inside Room transaction.
- fake repository behavior differs materially from real cache/serializer/transaction semantics.
- copied error mapper swallows cancellation or maps programmer defects to recoverable UI errors.

## Post-change audit

For every adapted pattern:

1. search for a second implementation of the same owner/policy;
2. test the invariant under rapid duplicate action;
3. test cancellation and stale completion;
4. test failure before and after the side-effect boundary;
5. test recreation/process behavior required by contract;
6. test account/key isolation;
7. inspect cleanup in `finally`/`awaitClose`/transaction rollback;
8. verify the test fails when the essential guard is removed or fault-injected.

### Evidence record

```text
Pattern selected and invariant:
Repository-specific adaptations:
Duplicate/stale evidence:
Cancellation/cleanup evidence:
Durability/restoration evidence:
Account/key isolation:
Fault-injected oracle check:
Template assumptions not applicable:
```
