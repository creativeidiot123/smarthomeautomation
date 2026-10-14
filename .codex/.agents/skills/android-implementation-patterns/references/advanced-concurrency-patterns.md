## Additional robust patterns

### Durable critical outcome with acknowledgement

Use this only when a completion must survive recreation/process restoration and can be represented by a stable ID. Persist or save the outcome in the appropriate owner; the UI performs the action idempotently and acknowledges that ID. Never treat an in-memory event stream as durable delivery.

```kotlin
@JvmInline
value class OutcomeId(val value: String)

data class Completion(
    val id: OutcomeId,
    val destinationId: ItemId,
)

data class ExampleUiState(
    val content: Content,
    val pendingCompletion: Completion? = null,
)

sealed interface ExampleAction {
    data class CompletionHandled(val id: OutcomeId) : ExampleAction
}

fun onCompletionHandled(id: OutcomeId) {
    _state.update { current ->
        if (current.pendingCompletion?.id == id) {
            current.copy(pendingCompletion = null)
        } else {
            current
        }
    }
}
```

The acknowledgement must be idempotent. For process-death durability, `pendingCompletion` must come from `SavedStateHandle` or persistent truth, not only ordinary ViewModel memory. Navigation still needs single-top/back-stack protection because acknowledgement can race with recreation.

### Operation generation when cancellation is advisory

Some clients continue work after coroutine cancellation. Pair cancellation with a monotonically increasing operation ID and commit only if current:

```kotlin
private var loadGeneration = 0L
private var loadJob: Job? = null

fun refresh() {
    val generation = ++loadGeneration
    loadJob?.cancel()
    loadJob = viewModelScope.launch {
        val result = repository.refresh()
        if (generation != loadGeneration) return@launch
        applyRefreshResult(result)
    }
}
```

This prevents stale state writes but does not make a non-idempotent mutation safe. The repository/server still needs idempotency or serialization for writes.

### Account-scoped single flight

A global “isLoading” flag is wrong when operations are keyed. Store in-flight state by stable key and clear it in `finally` without clearing a newer operation:

```kotlin
private val inFlight = mutableMapOf<AccountId, Job>()

fun sync(accountId: AccountId) {
    if (inFlight[accountId]?.isActive == true) return

    lateinit var job: Job
    job = viewModelScope.launch(start = CoroutineStart.LAZY) {
        try {
            repository.sync(accountId)
        } finally {
            if (inFlight[accountId] === job) {
                inFlight.remove(accountId)
            }
        }
    }
    inFlight[accountId] = job
    job.start()
}
```

Prefer repository/worker ownership when the operation outlives the screen. Protect shared maps with the owner thread or a mutex when accessed concurrently.
