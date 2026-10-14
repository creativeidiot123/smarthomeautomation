---
name: android-coroutines-flow-work
description: "Use for coroutines, Flow, callbacks, or background-work issues: cancellation, races, latest-wins, single-flight, serialization, WorkManager, RxJava migration, lifecycle collection."
---

# Coroutines, Flow, Background Work, and Rx Migration

## Start with ownership and semantics

For every asynchronous operation, identify:

```text
Owner scope:
Start trigger:
Stop/cancel trigger:
Concurrency policy:
Result destination:
Retry/idempotency:
Process-death requirement:
Backpressure/drop policy:
```

Do not add a coroutine merely because an API is suspend. Determine which owner is responsible for launching it.

## Structured concurrency

- Child work belongs to a parent scope with a clear lifetime.
- Use `viewModelScope` for screen orchestration; lifecycle/composition scopes for UI-only effects; explicit app/repository scope for work intentionally surviving a screen; WorkManager for durable deferrable work.
- No `GlobalScope`, raw thread, ad-hoc untracked `CoroutineScope`, or scope stored in a mutable singleton.
- Do not add `Job()`/`SupervisorJob()` to `withContext`; that detaches work from the caller.
- A scope injected into a repository must have a documented owner and shutdown policy.

### `coroutineScope` versus `supervisorScope`

Use `coroutineScope` when all children form one operation and one failure should cancel siblings. Use `supervisorScope` when independent children may fail separately and each failure is explicitly observed/handled.

Do not use supervision to hide failures. Unawaited `async` can retain or surface exceptions unexpectedly; use `launch` for fire-and-observe child work and `async` only when a value is awaited.

## Dispatchers and main safety

A suspend boundary that performs blocking work owns the dispatcher change. Callers should be able to call it from main safely.

- blocking file/socket/legacy SDK call: `withContext(IO)` at the boundary;
- CPU-heavy parsing/image/crypto: `withContext(Default)` or chunked computation;
- UI mutation: main owner;
- normal Room suspend/Flow and standard Retrofit suspend calls are already main-safe; avoid redundant dispatcher hopping;
- Flow context changes affect upstream via `flowOn`; do not assume downstream collector context changes;
- inject dispatchers when tests or ownership need control and the repository has/benefits from that seam, not as universal ceremony.

Never use `Dispatchers.Unconfined` in production behavior unless a library test explicitly requires it.

## Cancellation

- Never convert `CancellationException` into failure UI/logging.
- Broad catch blocks rethrow cancellation first.
- `runCatching` catches cancellation; avoid it in suspend code or use a cancellation-preserving helper.
- Long loops call `ensureActive()` or suspend periodically.
- Callback bridges unregister listeners on cancellation.
- `finally` runs on cancellation. Use `NonCancellable` only for short mandatory cleanup in `finally`, never to make ordinary work uncancellable.
- Treat `TimeoutCancellationException` as cancellation unless timeout is intentionally mapped to a domain timeout outcome.
- Ensure cancelled writes do not leave partially committed state; use transactions/idempotency/compensating policy.

## Concurrency policy

Choose explicitly:

| Policy | Typical use | Implementation direction |
|---|---|---|
| latest-wins | search/read preview | `flatMapLatest`, cancel prior job, operation token |
| first-wins/drop while busy | submit/pay/save button | state guard + repository single-flight |
| single-flight/coalesce | refresh same key | shared deferred/mutex keyed by request |
| queue | ordered mutations/messages | actor/channel/Mutex-backed queue with owner |
| merge/parallel | independent reads | `coroutineScope` + awaited `async` |
| idempotent retry | sync/upload | stable operation ID/idempotency key |

`flatMapLatest` is for cancellable reads or replaceable work. Do not use it for non-idempotent writes unless cancellation semantics are explicitly safe.

A UI-disabled button improves UX but is not the only duplicate guard. Put correctness at the mutation owner too.

## Flow type selection

- `Flow<T>`: cold stream; work starts per collector.
- `StateFlow<T>`: current state with an initial value and replay of latest.
- `SharedFlow<T>`: broadcast events/stream with explicit replay/buffer policy.
- `Channel<T>`: queue/hand-off with capacity and receiver semantics.

Never expose mutable variants publicly.

### Cold-flow hazards

Each collector can repeat network/database/callback work. Decide whether to keep cold, cache/share, or move work to a repository-owned source. Avoid accidental duplicate refresh on configuration change.

### `stateIn` / `shareIn`

Define:

- owning scope;
- replay count;
- `SharingStarted` policy;
- stop timeout and replay expiration;
- whether restarting upstream is safe;
- error completion behavior.

`WhileSubscribed` is often suitable for UI-facing state, but not automatically correct for expensive or durable work. `Eagerly`/`Lazily` can keep upstream alive for the scope lifetime.

## Operator semantics

- `map`: pure transformation;
- `onEach`: side effect that is safe on every collection/emission;
- `combine`: latest value from each source;
- `zip`: pair emissions one-for-one;
- `flatMapLatest`: cancel prior inner read;
- `flatMapMerge`: concurrent inner flows with bounded concurrency;
- `flatMapConcat`: sequential inner flows;
- `debounce`: wait for quiet period; typically user input;
- `distinctUntilChanged`: suppress equivalent values;
- `conflate`: skip intermediate values when only latest matters;
- `buffer`: decouple producer/consumer with explicit capacity;
- `retryWhen`: retry selected transient upstream failures with bounds;
- `catch`: handles upstream exceptions, not downstream collector failures.

Do not launch coroutines or emit one-time events from pure `map/combine` transforms. Transforms may rerun on every emission and re-subscription.

Avoid calling `.first()` on another repeatedly-created Flow inside `map`; use `combine` or restructure ownership when streams are related.

## Backpressure and event delivery

Document what happens when the consumer is slower or absent.

- state can conflate to latest;
- audit/payment/mutation events usually must not drop;
- telemetry may use bounded drop policy;
- `tryEmit`/`trySend` can fail; check the result when loss matters;
- replaying effects can cause duplicate navigation/snackbars;
- zero-replay effects emitted while no collector is active are lost;
- no in-memory primitive survives process death.

Critical outcome = persisted/replayable state plus idempotent handling, not an event promise.

## Callback bridges

One-shot:

```kotlin
suspendCancellableCoroutine<Result> { continuation ->
    val callback = object : Callback {
        override fun onSuccess(value: Result) {
            if (continuation.isActive) continuation.resume(value)
        }

        override fun onFailure(error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
    register(callback)
    continuation.invokeOnCancellation { unregister(callback) }
}
```

Stream:

```kotlin
callbackFlow {
    val listener = Listener { value -> trySend(value).isSuccess }
    register(listener)
    awaitClose { unregister(listener) }
}
```

Handle synchronous callback-before-registration-return, duplicate callbacks, close/error, thread safety, and cancellation.

## WorkManager

Use for deferrable work that must continue after process death/restart and can obey system scheduling. Do not use it for ordinary screen work or exact-time alarms.

Define:

- unique work name and ExistingWorkPolicy;
- constraints (network, charging, storage, battery);
- input/output size limits and persistence of large payloads elsewhere;
- idempotent operation key;
- `Result.success`, `retry`, and `failure` mapping;
- bounded backoff;
- foreground service requirements for long/expedited user-visible work;
- cancellation and cleanup;
- account/logout invalidation;
- progress semantics;
- worker DI and test setup.

Prefer `CoroutineWorker` for suspend APIs. Never report retry for permanent validation/auth failures. Avoid periodic work as a substitute for event-driven refresh when the product needs immediate action.

## RxJava migration

Migrate incrementally and preserve semantics.

| Rx | Coroutines/Flow direction |
|---|---|
| `Single<T>` / `Maybe<T>` | suspend result/nullable or typed outcome |
| `Completable` | suspend mutation |
| `Observable<T>` / `Flowable<T>` | `Flow<T>` with explicit hot/backpressure semantics |
| `BehaviorSubject` | `StateFlow` when it truly represents current state |
| `PublishSubject` | `SharedFlow`/Channel based on broadcast/queue semantics |
| `CompositeDisposable` | owner scope and structured cancellation |
| schedulers | boundary dispatcher ownership |

Audit:

- cold vs hot and subscriber count;
- error terminal behavior;
- retry/repeat;
- backpressure strategy;
- ordering/concurrency (`switchMap`, `concatMap`, `flatMap`);
- cancellation/disposal side effects;
- replay/cache behavior;
- threading (`subscribeOn`/`observeOn`);
- process/lifecycle owner.

Do not mechanically replace every operator by a similarly named Flow operator without semantic tests.

## Testing

Use `runTest`, virtual time, controlled dispatchers, and fakes. Never use real `delay` sleeps as synchronization. Test:

- cancellation propagation;
- timeout mapping;
- latest/first/single-flight/queue behavior;
- dropped/buffered event policy;
- retry bounds and backoff calculation;
- callback unregister;
- shared upstream collector count;
- WorkManager retry/idempotency/constraints;
- Rx migration equivalence.

## Anti-patterns

- `GlobalScope.launch`;
- `runBlocking` on app runtime/main;
- hardcoded dispatcher in every class without need;
- redundant `withContext(IO)` around Room/Retrofit;
- `catch (Exception)` returning success/empty;
- `runCatching` swallowing cancellation;
- `async` followed immediately by `await` with no concurrency;
- unawaited `async`;
- `callbackFlow` without `awaitClose`;
- public mutable hot flow;
- effect collected as state;
- `flatMapLatest` around writes;
- manual `Job?` churn when a Flow pipeline naturally owns latest-wins;
- leaked repository scope;
- unbounded retry or unbounded channel;
- WorkManager for immediate UI operation.


## Deep implementation protocol

### Choose concurrency semantics before operators

| Intent | Suitable semantics | Typical mechanism | Main risk to test |
|---|---|---|---|
| search/filter/typeahead | latest wins | `mapLatest`/`flatMapLatest` or cancel prior job | stale result cannot overwrite |
| submit/payment/save | first wins or single flight | mutex/in-flight state/disabled action/idempotency key | duplicate mutation |
| ordered durable writes | queue | actor/serialized repository/Room outbox | order and retry |
| independent reads | allow concurrent | `coroutineScope` + `async` | sibling failure policy |
| best-effort independent children | supervised | `supervisorScope` | failed child reported, not hidden |
| stream combination | merge/combine by product rule | explicit Flow operator | backpressure and partial data |

Operator choice must follow this table, not precede it. Never use latest-wins cancellation for a mutation unless cancellation is explicitly safe and the server/data layer is idempotent.

### Ownership and cancellation proof

For each launched coroutine, identify the owning scope, cancellation trigger, child relationship, exception destination, and resource cleanup. A scope must not live longer than any captured reference. A callback bridge must unregister exactly once and close resources even on cancellation.

Dispatcher use must be evidence-based:

- suspend network and modern Room APIs are generally main-safe through their libraries;
- CPU-heavy mapping belongs on `Default` when material;
- blocking file/legacy APIs belong on `IO`;
- do not scatter `withContext(IO)` around already-suspending calls merely to appear safe;
- expose main-safe repository APIs when the repository owns blocking implementation details.

### Flow semantics proof

Record whether a Flow is cold/hot, replayed, shared, stateful, conflated, finite/infinite, and how many upstream subscriptions are allowed. Confirm `stateIn/shareIn` start policy and scope match actual visibility and resource cost.

## AI-generated code hazards

- `GlobalScope`, ad-hoc `CoroutineScope(...)`, or a scope stored without cancellation ownership.
- `launch` nested inside suspend functions to escape structured concurrency.
- broad `catch` converting `CancellationException` into error UI or retry.
- `flowOn` placed after stateful operators with misunderstood effect, or used as a generic threading fix.
- `withContext(IO)` around every suspend call, obscuring main-safety ownership.
- `collect` launched repeatedly from recomposition/action without cancelling the previous collector.
- `shareIn/stateIn` in application scope for screen-only data, causing permanent subscriptions/cache.
- `callbackFlow` missing `awaitClose`, unregister, close, or thread-safe callback handling.
- unlimited `buffer`, `Channel.UNLIMITED`, or unbounded retry loops hiding backpressure.
- `flatMapLatest` for writes, silently cancelling side effects.
- WorkManager used for immediate screen work, or ViewModel scope used for work that must survive process death.
- worker returns success after partial failure, retries non-idempotent work, or starts unmanaged coroutines after `doWork` returns.
- tests that use real delays and therefore do not prove timing/order deterministically.

## Post-change audit

### Concurrency adversary set

Test at least the applicable cases with controlled scheduling:

- old request completes after new request;
- cancellation arrives before, during, and after boundary call;
- action is triggered twice before first completion;
- upstream throws while sibling/collector is active;
- collector stops and restarts;
- callback emits concurrently with cancellation;
- worker retries after process restart;
- network/DB work partially succeeds before failure.

### Static/runtime checks

- Search for every new `launch`, `async`, scope, `stateIn`, `shareIn`, `callbackFlow`, retry, buffer, mutex, and worker enqueue site.
- Verify jobs are not silently replaced without cancellation or generation guards.
- Count expected upstream subscriptions in a test or instrumentation log when duplicate collection is the risk.
- Assert cancellation is rethrown and no UI error is emitted for cancellation.
- For worker changes, test constraints, unique-work policy, idempotency, retry/backoff, and terminal result.
- Ensure no main-thread blocking API was introduced; use StrictMode or trace evidence when static inspection is insufficient.

### Evidence record

```text
Chosen concurrency policy:
Owning scope and cancellation trigger:
Hot/cold/share semantics:
Duplicate/stale test:
Cancellation test:
Worker durability/idempotency test:
Threading evidence:
Residual race:
```
