# HomeDJ good/bad pairs

Use these as templates. Each "bad" shape is one that AI agents commonly write; each "good" shape uses what this repo already has. If a pair does not fit your case, follow the owning code, not this file.

## Errors

Bad: swallows failure and hides cancellation inside a suspend path.

```kotlin
val catalog = runCatching { SavedPlaylistStore.shared(app).read() }.getOrNull()
```

Good: cancellation is rethrown; failure is visible, logged once with a safe reason, and mapped to explicit state.

```kotlin
val catalog = try {
    SavedPlaylistStore.shared(app).read()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Log.w("HomeDJ", "Saved playlist read failed: " + failure.javaClass.simpleName)
    return LibraryState.Unavailable
}
```

## Coroutine scope

Bad: a scope nobody cancels.

```kotlin
fun startSync() = CoroutineScope(Dispatchers.IO).launch { refreshPlaylists() }
```

Good: work belongs to an owner that already exists: `viewModelScope` for screen work, the service's `SupervisorJob` scope cancelled in `onDestroy` for service work, or WorkManager for work that must survive the process (`AutoLibraryConfig.schedule()`).

## Playback ownership

Bad: a second player, or the Controller playing audio itself.

```kotlin
val player = ExoPlayer.Builder(context).build() // in a ViewModel or RemoteControllerViewModel
```

Good: send a command to the one session `PlaybackService` owns.

```kotlin
controller.setMediaItems(items) // MediaController connected to PlaybackService's SessionToken
controller.prepare()
controller.play()
```

On the Controller, send a remote command (`playTrack`, `playPlaylist`) and render Samsung's reply.

## Role gate

Bad: role read once and trusted for a long operation.

```kotlin
val isServer = modeStore.mode.first() == HomeDjMode.SERVER
repeat(150) { download(it) } // role may change in between
```

Good: pass a re-checking predicate and call it at every commit point, like `OfflineAudioWorker`'s `authorized`.

```kotlin
val authorized: suspend () -> Boolean = { modeStore.mode.first() == HomeDjMode.SERVER }
if (!authorized()) return Result.success()
OfflineAudioLibrary.download(applicationContext, id, automatic, authorized)
```

## Remote commands

Bad: the Samsung trusts what the Controller sends.

```kotlin
"playTrack" -> active.setMediaItem(MediaItem.fromUri(request.getString("url")))
```

Good: the Controller sends only stable IDs; Samsung validates membership in its own catalog (`RemoteLibraryCommands`) and builds the media item itself. Unknown commands return `ok=false` with `Unknown command` so an older or newer peer degrades cleanly.

## Wire and persisted names

Bad: renaming for tidiness.

```kotlin
.putString("trackId", track.id)  // was "videoId"; queued WorkManager jobs still carry "videoId"
enum class HomeDjMode { HOST, REMOTE } // stored by name in DataStore
```

Good: keep the persisted/wire name. If a rename is truly needed, read the old name, write the new one, and add a test that decodes the old form.

## Nulls

Bad (fails `HomeDjGuardrailTest`):

```kotlin
val key = RemoteChannel.decodeKey(prefs.savedClientKey)!!
```

Good: handle the missing case explicitly.

```kotlin
val key = RemoteChannel.decodeKey(prefs.savedClientKey)
    ?: return state.copy(connected = false, error = "Enter the server's private Wi-Fi IPv4 and pairing code.")
```

## Timing

Bad: a delay pretending to be synchronization.

```kotlin
RemoteServerService.setEnabled(context, true)
delay(500) // wait for the listener
refreshStatus()
```

Good: observe the state the owner publishes (`RemoteServerService.running`), or wait for the owner's reply. If the owner has no completion signal, that is the bug to fix. Backoff waits come only from `statusRetryDelayMillis`.

## Blocking I/O

Bad: a socket, file, or HTTP call on Main, or a busy loop that ignores cancellation.

Good: `withContext(Dispatchers.IO)` at the boundary that blocks (`NativeAudioSource`, `OfflineAudioLibrary.download`, `SavedPlaylistStore`), `coroutineContext.ensureActive()` inside loops, and close the socket or `AudioRecord` to unblock a read on shutdown. Do not wrap DataStore edits; they are already main-safe. Touch `MediaController` only on Main.

## Exposed state

Bad:

```kotlin
val state = MutableStateFlow(RoleUiState())
```

Good:

```kotlin
private val mutableState = MutableStateFlow(RoleUiState())
val state = mutableState.asStateFlow()
```

## Modes

Bad: Booleans that can contradict each other.

```kotlin
data class CastUi(val casting: Boolean, val connecting: Boolean, val failed: Boolean)
```

Good: one exclusive mode, or Booleans with a documented invariant enforced by the single owner that writes them.

## Ceremony

Bad: a class that only forwards one call, or a new `XyzManager`/`XyzHelper`/`XyzCoordinator` "to keep it clean".

```kotlin
class GetModeUseCase(private val store: ModeStore) { operator fun invoke() = store.mode }
```

Good: call `store.mode` directly. Add a function to the existing owner or an `internal` top-level function beside it. If the owner file is already over 500 lines, put the new cohesive logic in a new file beside it; don't create a new layer.

## Compose loading

Bad: the UI decides when data loads, or touches an owner directly.

```kotlin
LaunchedEffect(Unit) { OfflineAudioLibrary.enqueue(context, track) }
```

Good: the click dispatches to the ViewModel or Activity callback, the owner enqueues, and the UI collects with `collectAsStateWithLifecycle()`.

## Logging

Bad: dumps whole objects or secrets.

```kotlin
Log.d("HomeDJ", "resolved $link for $accountName with token=$token")
```

Good: operation, safe reason, and IDs or counts only.

```kotlin
Log.w("HomeDJOffline", "Audio download failed: videoId=" + id + " error=" + failure.javaClass.simpleName)
```

Never log tokens, pairing keys, signed/stream URLs, account names, LAN addresses, or PCM.

## Workers

Bad: `class SyncWorker(ctx: Context, params: WorkerParameters, private val repo: Repo)` crashes with the default WorkerFactory. An interactive Google sign-in launched from a Worker cannot be shown.

Good: `(Context, WorkerParameters)` only; build owners from `applicationContext`, use silent authorization and return `Result.success()` when a resolution is needed, bound retries with `runAttemptCount`, like `AutoLibraryWorker` and `OfflineAudioWorker`.

## Foreground services

Bad: work before `startForeground`, or the wrong service type.

Good: call `startForeground` with the matching type (`mediaProjection`, `connectedDevice`, `mediaPlayback`) before anything that can `stopSelf()`, then check permissions; release sockets, futures, and audio objects in `onDestroy`.

## Tests

Bad: assert that a flag you just set is set, or that a fake was called.

Good: drive the real pure owner function (`SavedPlaylistCodec`, `RemoteChannel.send/receive`, `statusRetryDelayMillis`, `YouTubePlaylistLinks`, `networkForDownload`) and assert the observable result, including the invalid and old-format inputs. Confirm the test fails with the fix reverted.
