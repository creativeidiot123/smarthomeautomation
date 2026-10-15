# AGENTS.md — HomeDJ Kotlin/Android Contract

Production Kotlin/Android repository for **HomeDJ**. Make the smallest correct change that preserves playback ownership, user data, pairing security, lifecycle, wire compatibility between the two phones, and release behavior.

Default bias: **inspect first; one owner per fact; explicit async/lifecycle/duplicate semantics; proof proportional to risk.**

Do not add dependencies, plugins, modules, wrappers, caches, layers, generated code, or runtime services unless materially required and no simpler repository-compatible pattern exists.

**Kotlin size gate:** new files stay ≤500 handwritten lines. Files already over 500: do not split them during unrelated work and do not grow them by more than ~30 lines; put new cohesive logic in a new file beside the owner. A split is its own task.

**Research gate:** when a change uses an API, library, or platform behavior this repo does not already use (or a version/OEM edge case: One UI, OxygenOS, Android 14+ FGS rules), read official docs/source for the exact versions and search GitHub for implementations, issues, and regressions first. Repository contracts and official sources win. Never cargo-cult third-party code or architecture.

## Project golden rule

**Simple, functioning, efficient, stable code. Simpler, maintainable architecture.**

Avoid over-engineering, unnecessary abstractions, duplicated ownership, hidden state, fragile workarounds, leaks, loops, races, and unnecessary complexity.

## 0. HomeDJ quick reference

- **App:** one module `:app`, flat package `dev.homedj.app`, minSdk 29 / targetSdk 35 / compileSdk 36, JDK 17. One APK, two device-local roles stored in `ModeStore`: **Server** (Samsung M21 driving the speakers; owns the only player, downloads, Google account, LAN listener) and **Controller** (OnePlus 12; remote UI and live audio capture only).
- **Has:** Compose + Material 3 (Expressive styling), Media3 ExoPlayer/`MediaLibraryService`/`MediaController`, Preferences DataStore, SharedPreferences, app-private JSON/files, WorkManager, NewPipeExtractor v0.26.5 (JitPack, **GPL-3.0-or-later**), Google `AuthorizationClient` (`youtube.readonly`), YouTube Data API v3 over `HttpURLConnection`, `org.json`, raw TCP + AES-256-GCM on port 47188, MediaProjection + `AudioRecord`/`AudioTrack`, core library desugaring, JUnit4 JVM tests.
- **Has not:** Room, Application subclass/composition root, Hilt/Koin, Retrofit/OkHttp/Ktor, kotlinx.serialization, Navigation Compose, Paging, version catalog, Gradle wrapper, R8/minified release, instrumentation/Robolectric/MockK/Truth, Play Billing, ads, backend of our own, Play Store listing. Rows about those are N/A unless the task adds them (adding any of them needs explicit user approval).
- **Construction:** framework entry points build thin owners from application context: `AndroidViewModel(application)` with the default factory, Workers with `(Context, WorkerParameters)` only, Services in `onCreate`. Shared process owners are `object`s (`OfflineAudioLibrary`, `NativeAudioSource`, `RemoteChannel`, `LiveAudioSink`) or `SavedPlaylistStore.shared(context)`. Never construct a second `SavedPlaylistStore`, player, `MediaLibrarySession`, or DataStore delegate. Do not add a DI framework or Application class.
- **Reuse before writing:** `ModeStore.mode` (role gate), `RemoteChannel.exchange/serve` (all LAN I/O), `RemoteLibraryCommands` (validated remote library actions), `OfflineAudioLibrary.enqueue/existing/workName` (all downloads), `NativeAudioSource.validId/resolve` (video IDs and streams), `SavedPlaylistCodec` (persisted JSON), `statusRetryDelayMillis` (controller backoff), `LiveAudioFormat` (PCM format), `collectAsStateWithLifecycle()`.
- **Error idiom:** `try { … } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { Log.w(TAG, "<operation> failed: " + failure.javaClass.simpleName) }`. `runCatching` only in non-suspend parsing/cleanup code.
- **Logging:** `android.util.Log` with tags `HomeDJ`, `HomeDJRemote`, `HomeDJCast`, `HomeDJOffline`, `HomeDJAuto`. One line per failure path: operation + exception simple name + safe IDs/counts. Never log tokens, pairing keys, signed/stream URLs, account names/emails, LAN addresses, request/response bodies, or PCM.
- **Hot zones (Risky):** `RemoteChannel`, `RemoteServerService`, `RemotePrefs`, `RemoteLibraryCommands`, `RemoteControllerViewModel` (crypto, pairing, LAN wire protocol); `LiveAudioCastService`, `LiveAudioSink`, `LiveAudioFormat` (MediaProjection/FGS/audio); `PlaybackService`, `HomeDjMediaResolver`, `NativeAudioSource` (sole player, stream resolution, NewPipe); `OfflineAudioLibrary`, `OfflineAudioWorker`, `AutoLibraryWork` (files, quotas, WorkManager); `SavedPlaylistStore`, `ModeStore`, any persisted key; OAuth in `HomeDjActivity`/`YouTubeViewModel`; `AndroidManifest.xml`; Gradle files; `.github/workflows`.
- **Guard test (never weaken):** `HomeDjGuardrailTest` bans `GlobalScope`, `runBlocking`, `Thread.sleep`, `!!`, raw `collectAsState(`, destructive migration, backup/cleartext enablement, and checks that the agent instruction/skill mirrors stay identical and free of foreign-project references.
- **Commands (no wrapper; Gradle ≥ 8.13 — CI pins 8.13; one Gradle run at a time; always `--no-daemon --console=plain`):** `gradle :app:compileDebugKotlin`; `gradle :app:testDebugUnitTest --tests "dev.homedj.app.<Class>"`; CI gate `gradle :app:testDebugUnitTest :app:assembleDebug`. If `gradle` is not on PATH, use Android Studio's Gradle or a ≥8.13 distribution under `~/.gradle/wrapper/dists`. Do not add a wrapper unasked.

## HomeDJ dos and don'ts

| Area | Do | Don't |
|---|---|---|
| Playback | Route every play/pause/queue change through a `MediaController` to `PlaybackService`; queue stable internal video-ID URIs and let `HomeDjMediaResolver` resolve at load time | Create a second `ExoPlayer`/`MediaSession`, play YouTube audio on the Controller, queue signed URLs, or restore queues as if Media3 persisted them |
| Role | Re-check `ModeStore.mode.first() == SERVER` at execution time in workers, remote handlers, and long transfers | Trust a role read at screen start or cached in memory; rename `HomeDjMode` constants (stored by `name`) |
| LAN protocol | Keep one authenticated request per short-lived socket, fresh challenge as AES-GCM AAD, `MAX_FRAME` bounds, `ok`/`error` replies, allowlisted commands, server-side validation via `RemoteLibraryCommands` | Add plaintext/fallback modes, new listening ports, mDNS/discovery, unbounded reads, or controller-trusted IDs; send Google tokens, files, or stream URLs to the Controller |
| Wire compatibility | Assume the two phones run different builds: new request/response fields are optional, unknown commands return `Unknown command`, old commands keep their meaning | Rename/remove command strings or JSON fields, change the port, frame layout, or PCM format without a versioned migration plan |
| Live audio | Fixed 48 kHz stereo 16-bit 20 ms packets (`LiveAudioFormat`); bounded buffers; close the socket/`AudioRecord` to unblock I/O; `startForeground` with type `mediaProjection` before anything that can `stopSelf()` | Reuse a MediaProjection consent token, start capture from the background, persist/log PCM, or claim DRM/call/system-wide capture |
| Downloads | Enqueue only through `OfflineAudioLibrary.enqueue` (unique work per ID, `KEEP`); write `.part` then rename under `commitMutex`; keep the 100 MiB / 2 GiB caps and unmetered auto jobs | Write audio elsewhere, convert formats, rename work names or input keys (`videoId`, `accountKey`, `playlistId`) that already-queued work depends on, or retry unbounded |
| Google/YouTube | Tokens in memory only (`YouTubeViewModel`); workers use silent authorization and skip when a resolution is needed; disconnect erases metadata and auto-sync selection | Persist or log tokens, launch interactive auth from a Worker, pass OAuth to NewPipe, bypass DRM/age/login restrictions, or store cookies |
| NewPipe / GPL | Keep NewPipe behind `NativeAudioSource`; HTTPS GoogleVideo audio only; bounded responses; document GPL impact of new linked code | Copy source from NewPipe, Metrolist, or InnerTube; add another extractor or shell/Termux execution |
| Services | Correct `foregroundServiceType` per service (`mediaPlayback`, `connectedDevice`, `mediaProjection`) and matching manifest permissions; release sockets/controllers/futures in `onDestroy`; `exported=false` unless Media3 requires it | Do long work in `onStartCommand` on Main, leak `MediaController` futures, or export new components |
| UI | Compose renders ViewModel/service state and emits actions; `collectAsStateWithLifecycle()`; tab and dialog state in `rememberSaveable`; Material 3 Expressive per `m3-expressive` | Call stores, sockets, WorkManager, or `NativeAudioSource` from composables; add Navigation Compose for in-screen tabs; show success before the owner confirms |
| Threads | Blocking sockets/files/HTTP on `Dispatchers.IO` at the boundary that blocks; `MediaController` on Main (as `RemoteServerService.handle` does) | Block Main, sleep to "wait", or use unmanaged threads/scopes |

## Operating behavior

### Think before coding

**Do not assume or hide ambiguity.**

Before implementing:
- State material assumptions. Ask before choices affecting data loss (downloads, saved metadata, pairing), security (pairing key, LAN exposure, OAuth scopes), irreversible behavior, wire/persisted contracts, licensing, or major UX.
- For reversible low-risk ambiguity, choose the smallest compatible behavior and record it.
- Surface simpler tradeoffs; never invent backend guarantees, YouTube/extractor guarantees, or OEM background guarantees.

### Simplicity first

**Minimum code; nothing speculative.**

- No unrequested features, one-use abstractions, or speculative configurability.
- Do not defend states impossible by types/invariants. Treat persisted/platform/IPC/LAN/HTTP/extractor/file/SDK boundaries as fallible and map failures at their owner.
- If 200 lines could be 50, rewrite it.

### Surgical changes

**Touch only what the request requires.**

- Do not improve adjacent code, comments, formatting, or architecture.
- Match existing style (flat package, small files, `internal` where possible, explicit try/catch idiom). Mention unrelated dead code; do not remove it.
- Remove only imports/variables/functions made unused by your change.
- Every changed line must trace to the request.

### Goal-driven execution

Turn work into verifiable goals: reproduce a bug before fixing it, test invalid inputs before adding validation, and preserve before/after behavior during refactors. For defects, find the first bad state/root cause at its owner; never mask it with arbitrary delay/retry/refresh/reset, broad catch, suppression, or stacked guards unless that mechanism is justified by the operation semantics and evidence.

For multi-step work, state `step -> verification` briefly and loop until defined checks pass. Weak goals such as "make it work" require clarification.

## Product contract gate

For non-trivial features, use `android-product-contract` before implementation: goal/non-goals/journeys on **both phones**, owners/truth, data lifecycle, duplicate/retry/undo/unknown outcomes, restoration/permissions/security/privacy/platform constraints, and acceptance/forbidden outcomes as observable scenarios. Record low-risk assumptions.

## 1. Always-on invariants

1. **One authority per mutable fact.** Name who may decide and mutate it (see `architecture.md`).
2. **Truth differs from source, cache, and UI state.** YouTube API is upstream, `SavedPlaylistStore` is local metadata truth, Media3 session is playback truth, ViewModels are projections.
3. **Every async operation has semantics:** latest-wins, first-wins, single-flight, queue, merge, idempotent retry, or concurrent.
4. **Every operation has a lifecycle owner** and intentional stop/survive/transfer behavior.
5. **Every mutation defines duplicate/retry behavior** across taps, re-collection, retries, recreation, and repeated remote commands.
6. **Every cache defines key, owner, freshness, identity scope, invalidation, and removal** (e.g. resolver links: per video ID, per player-service instance, ≤4 minutes).
7. **Every boundary maps models/errors deliberately;** transport, file, framework, and sensitive details do not leak upward or across the LAN.
8. **UI renders state and emits actions;** it owns no store/socket/WorkManager/business truth.
9. **Navigation is a UI-edge effect;** data/service layers never navigate.
10. **Compilation is not proof.** Run a focused `android-post-change-audit` after implementation.
11. **Every edit classifies persistence and wire impact before implementation** (§10): DataStore, SharedPreferences, app-private JSON/files, WorkManager names/input data, LAN protocol fields.

If an invariant is unclear, inspect rather than inventing a convenient owner.

## 2. Instruction and skill use

**Pre-edit gate (scaled by §17 tier):** Tiny: read the target file and its direct callers. Normal/Risky: also read the relevant `project.md` and `architecture.md` sections, the matching skills (usually 1-3), and enough callers/callees/tests/wiring to identify the live owner. Search for an existing owner/helper/pattern before creating one.

For conflicts prefer: user-data/security safety; repository architecture/versions; lifecycle/cancellation/retry/recreation correctness; smaller verifiable changes; fewer assumptions/dependencies.

Never let a specialist skill silently trigger a broad migration. Version-specific or experimental guidance applies only when the project already uses that stack or the user explicitly requested the migration.

### Living project documentation

`project.md` and `architecture.md` are implementation contracts. Update `project.md` for verified toolchain/build/dependency/persistence/integration fact changes; update `architecture.md` for ownership, truth, lifecycle/concurrency, persistence, protocol, background-work, or enforcement changes. Record why unchanged for Normal/Risky patches. Code beats stale prose; exclude secrets, local paths, IPs, transient status, and plans.

### Agent files

`AGENTS.md` is the single source of truth (`CLAUDE.md` imports it). Skills live in `.claude/skills/` (Claude Code) and are mirrored byte-for-byte to `.agents/skills/` and `.codex/.agents/skills/`; `.codex/AGENTS.md` mirrors this file. Edit the `.claude` copy and this file, then sync the mirrors; `HomeDjGuardrailTest` fails on drift.

### Skill routing

Routing is additive: read all matching rows. Skill checklists/evidence records are aids; the required report is §17's.

| Work in HomeDJ | Skill |
|---|---|
| New feature/raw idea | `android-product-contract` |
| After any code/config edit | `android-post-change-audit` |
| Owners, file placement, new class/object | `android-architecture-ownership` |
| ViewModel state, Activity results/permissions, tabs, recreation | `android-state-navigation-lifecycle` |
| Coroutines, Flow, sockets loops, backoff, WorkManager | `android-coroutines-flow-work` |
| DataStore, SharedPreferences, `SavedPlaylistStore`, offline files, persisted keys | `android-data-room-sync-paging` |
| YouTube Data API, NewPipe, HTTP, LAN request/response reliability | `android-network-auth-reliability` |
| Compose / Material 3 / M3 Expressive | `android-compose-ui-effects` + `m3-expressive` |
| TalkBack, font scale, insets, layouts | `android-accessibility-adaptive-ui` |
| Tests, Logcat/ADB, crashes, flakiness | `android-testing-debugging` |
| Startup/jank/battery/audio latency | `android-performance-release` |
| Gradle/AGP/dependencies/CI/signing | `android-gradle-di-release` |
| Pairing, AES-GCM, OAuth, manifest exposure, logs, GPL/privacy | `android-security-privacy-identity` |
| Media3, MediaProjection, AudioRecord/AudioTrack, foreground services, notifications | `android-platform-camera-media` |
| Library/API migrations | `android-migration-modernization` |
| Implementing a known pattern | `android-implementation-patterns` (read its `references/homedj-good-bad.md`) |
| Play publishing/policy (not set up yet) | `android-google-play-developer` |
| Out of scope unless explicitly requested: KMP, Billing, Wear OS, XR, AppFunctions | `android-kmp-compose-multiplatform`, `android-play-billing-entitlements`, `android-wear-os`, `android-xr-glimmer`, `android-appfunctions-device-ai` |

## 3. Authority, truth, and source model

| Concept | Meaning | HomeDJ examples |
|---|---|---|
| Canonical authority | Decides facts and accepts/rejects mutations | `ModeStore` role, `RemotePrefs` pairing key, `AutoLibraryConfig` opt-ins, Media3 session queue |
| Upstream source | External producer | YouTube Data API, NewPipe extractor, Google AuthorizationClient, Samsung over LAN (for the Controller) |
| Local truth | Observable local record | `SavedPlaylistStore` catalog, `files/offline_audio`, DataStore Flow |
| Cache | Discardable acceleration copy | `HomeDjMediaResolver` signed links (≤4 min) |
| Projection | Derived read-only representation | `RoleUiState`, `PlaybackSnapshot`, remote/cast UI state |
| Pending work | Durable unconfirmed mutation | WorkManager download/sync jobs |
| System truth | Android/other-process state | permissions, MediaProjection consent, Wi-Fi network, FGS state |

Rules:
- ViewModel may own screen state, not durable authority.
- The Controller's view of Samsung is a polled projection; Samsung is the authority for playback, library, and downloads.
- An API/extractor response is not committed truth until the owning store/file commit succeeds.
- Memory is not truth merely because it is fast (`RemoteServerService.running` is a projection of the service, not a setting).
- Recompute derived values from their owner or invalidate them with it; do not synchronize duplicate copies manually.
- Include Google account, role, and playlist opt-in in keys/invalidation where relevant (`SavedPlaylistStore.accountKey`).
- Do not claim success before the authoritative outcome (Samsung reply `ok`, file rename, store write).
- Time is a dependency for expiry/backoff; use `SystemClock.elapsedRealtime()` for durations, not wall clock.

## 4. Lifecycle and scope model

| Owner | Appropriate work | Must not retain |
|---|---|---|
| Composition | UI effects, `DisposableEffect` attach/detach | Activity beyond composition; durable work |
| Activity | Activity Result/permission/MediaProjection launchers, OAuth resolution UI | feature work that must survive recreation |
| ViewModel | screen state, polling while composed, cancellable on-demand operations | Activity, View, Compose lambdas, UI-lifetime Context |
| Service scope (`SupervisorJob`) | LAN listener, Media3 session, capture loop; cancelled in `onDestroy` | UI references, unbounded per-client work |
| Process `object` | stateless helpers, bounded locks/caches | Activity/Service references, per-screen collectors |
| WorkManager | downloads, periodic metadata sync | navigation, UI state, interactive auth, non-idempotent retry |

Rules:
- Structured concurrency; parent owns child cancellation. Register/unregister callbacks (network callbacks, Media3 listeners, futures) in the same owner.
- Configuration recreation is not process survival; Media3 queues are not restored after process death.
- Transfer work that must outlive the screen to a service or WorkManager; do not lengthen scope for work that should stop on leave.
- Account disconnect clears metadata and auto-sync selection; role change away from Server stops playback, listener, and authorized downloads.
- START_STICKY restarts must re-check role and enabled preferences before accepting commands.

## 5. Concurrency and operation semantics

| Operation | Default | Protection |
|---|---|---|
| Controller status poll | single loop, latest-wins | one poll job per ViewModel; backoff via `statusRetryDelayMillis` |
| Remote command tap | first-wins per action | in-flight guard/`Mutex` in `RemoteControllerViewModel`; Samsung validates |
| Download save | idempotent per video ID | unique work `KEEP`, `.part` + rename, `commitMutex` quota check |
| Periodic sync | unique periodic work `KEEP` | account/opt-in re-check between steps, ≤150 tracks per pass |
| Live cast | one at a time | `castSocket` compare-and-set; new playback command ends cast |
| LAN sessions | concurrent, bounded | `Semaphore(6)`, handshake timeout, `soTimeout` |
| Metadata writes | serialized | `SavedPlaylistStore` mutex + atomic file replace |

Rules:
- Prior-state updates are atomic (`MutableStateFlow.update`, compare-and-set).
- Cancellation blocks obsolete UI commits but does not prove Samsung did not execute a command; re-read status.
- An in-process `Mutex` does not protect cross-process or cross-device invariants; use unique work, server-side validation, or atomic file renames.
- Retry only bounded, transient, idempotent work. A LAN write timeout is an unknown outcome; refresh status before repeating.
- Never silently drop business-critical events; dropping stale PCM frames under backpressure is intentional and bounded.

### Mandatory race audit

```text
Double tap / repeated remote command; older poll reply after newer
Role switch or Wi-Fi loss mid-command / mid-download / mid-cast
Service destroyed while a socket or MediaController future is pending
Worker retry after partial download; manual + automatic save of the same ID
Account switch/disconnect during sync; START_STICKY restart with stale prefs
Cancellation between side effect and state update
```

## 6. Execution protocol

1. **Resolve contract:** observable acceptance on each phone, forbidden outcomes, invariants, assumptions.
2. **Inspect:** owners in `architecture.md`, callers/callees, persisted keys, protocol fields, tests, build config.
3. **Map owners:** policy, authority, local truth, sources, projection, mutation, cache, lifecycle, concurrency, tests.
4. **Define states:** success/loading/empty/error/retry/cancel/stale/offline/duplicate/permission/role-inactive/peer-unreachable as relevant.
5. **Patch the nearest owning seam;** never route around it from UI or add a parallel owner.
6. **Preserve contracts:** persisted keys, WorkManager names/input keys, LAN commands/fields/port/frames, manifest components/permissions, `applicationId`, user-visible behavior.
7. **Implement minimally** with existing idioms and tests.
8. **Verify, audit, and report per §17/§18.**

## 7. Architecture boundaries

Default direction: `Compose UI -> ViewModel -> owner (store/library/repository/service) -> DataStore/SharedPreferences/files/HTTP/LAN/Media3`.

| Layer | Owns | Must not own |
|---|---|---|
| Composable | rendering, UI-local mechanics, actions, semantics | stores, sockets, WorkManager, extractor calls |
| `HomeDjActivity` | hosting, launchers (permissions, document picker, OAuth, MediaProjection) | business rules, retained view references |
| ViewModel | immutable screen state, validation, screen async, safe UI errors | Activity/View, durable truth, raw exceptions in UI |
| Owner objects/stores/repositories | source strategy, validation, mapping, mutation semantics | screen state, UI strings, lifecycle references |
| Services | foreground lifetime, sockets, Media3 session, capture | UI state, navigation |
| Workers | durable idempotent work with constraints | UI, interactive auth, unbounded retry |

A new class needs real policy, reuse, a transaction, or a test seam; a class that only forwards one call is noise. Prefer an `internal` top-level function next to the owner.

## 8. State, events, and navigation

- Expose immutable `StateFlow`; keep `MutableStateFlow` private (exception: existing companion `running`/`state` projections owned by their service).
- Model exclusive modes so impossible combinations cannot occur.
- Durable outcomes belong in the store/Media3/file system; event streams are only for transient presentation.
- There is no navigation library: tabs and sub-views are `rememberSaveable` UI state; truth always comes from ViewModels/services.
- Permission, document-picker, OAuth, and MediaProjection launchers stay in `HomeDjActivity`; ViewModels own the decision and result state.
- UI loading/disabled state is not a concurrency protocol; the owner guards duplicates.

## 9. Coroutines, Flow, and background work

- No `GlobalScope`, `runBlocking`, `Thread.sleep`, unmanaged scope/thread, or orphan job (guard test).
- The boundary performing blocking I/O owns `Dispatchers.IO`; don't wrap already main-safe suspend APIs (DataStore edits).
- Never swallow `CancellationException`; no `runCatching` around suspend calls.
- Use `async` only for real concurrency and always await. Choose `coroutineScope` vs `supervisorScope` by failure semantics.
- Callback APIs use `suspendCancellableCoroutine`/`callbackFlow` and unregister reliably.
- Blocking socket/`AudioRecord` loops stop by closing the resource; check `isActive`/`ensureActive()` in loops.
- Workers are idempotent, constrained, uniquely named, bounded in retries (`runAttemptCount`), and re-check role/opt-in.

## 10. Persistence, wire contracts, and data

**There is no Room database.** Persisted contracts that must stay readable by the next build:

| Store | Owner | Contract |
|---|---|---|
| DataStore `homedj_settings` | `ModeStore` | key `device_role` = `HomeDjMode.name` |
| SharedPreferences `remote_pairing` | `RemotePrefs` | `server_key`, `enabled`, `host`, `client_key` |
| SharedPreferences `homedj_auto_library` | `AutoLibraryConfig` | `enabled`, `playlists`, `accountKey` |
| App-private JSON | `SavedPlaylistStore` / `SavedPlaylistCodec` | field names, account key derivation, 4 MB bound, atomic replace |
| `files/offline_audio/<videoId>.<m4a/webm/opus>` | `OfflineAudioLibrary` | naming, `.part`/`.auto.part`, caps |
| WorkManager | `OfflineAudioLibrary`, `AutoLibraryConfig` | work names `homedj_audio_<id>`, `homedj_auto_audio_<id>`, `homedj_playlist_sync`; input keys `videoId`, `accountKey`, `playlistId`; worker class names |
| LAN protocol | `RemoteChannel` / `RemoteServerService` | port 47188, challenge/nonce/frame layout, command strings, JSON fields, PCM format |

Rules:
- Renaming/removing any key, field, enum constant, class name referenced by WorkManager, or file name is a migration: read old + write new, test both, and record it in `project.md`.
- Small settings in DataStore/SharedPreferences; lists/documents in the JSON store; secrets never in source/resources/BuildConfig. `allowBackup` stays `false` so the pairing key never leaves the device.
- Write files atomically (temp + rename); bound every read from disk, HTTP, and LAN.
- Never silently discard downloaded audio or saved metadata outside explicit user disconnect/clear flows.
- **Adding Room** is an architecture change needing approval; if approved, use exported schemas, explicit tested migrations, no `fallbackToDestructiveMigration`, and the Room gate in `android-data-room-sync-paging`.

## 11. Network, authentication, and errors

- HTTP stays on `HttpURLConnection` with explicit connect/read timeouts, HTTPS only (`usesCleartextTraffic=false`), no redirects for media downloads, content-type and size checks.
- YouTube Data API uses the in-memory OAuth token; 401/expired → re-authorize via `AuthorizationClient` (silent in workers), never loop.
- LAN: every request authenticated with the pairing key; failures map to fixed safe messages ("Server unavailable or pairing code incorrect.", "Command failed. Check Wi-Fi connection."); Samsung replies with fixed `error` strings; never echo raw exceptions over the wire or into UI.
- Preserve cancellation and treat timed-out writes as unknown outcomes.
- Retry only bounded transient/idempotent operations.
- Validate unknown/missing JSON fields with `opt*` defaults, IDs with `NativeAudioSource.validId`, playlist IDs with length/charset checks.
- Every failure path logs one safe `Log.w/e` line (§0 Logging).

## 12. Compose, UX, and accessibility

- UI renders state and emits actions; no store/socket/WorkManager work from composition or click lambdas beyond dispatching to a ViewModel/Activity launcher.
- Collect with `collectAsStateWithLifecycle()`; choose effect keys intentionally; clean listeners in `DisposableEffect`.
- Never write state during composition or do expensive transforms in hot list item paths; Lazy lists use stable keys (video/playlist IDs).
- Public reusable composables accept `Modifier`; leaf components do not receive ViewModels.
- Follow Material 3 Expressive (`m3-expressive`) and dynamic color; support font scaling, TalkBack labels, ≥48dp targets, real insets.
- Mask the pairing code until revealed; never show tokens, URLs, or stack traces.

## 13. Security, privacy, storage, and platform boundaries

- The LAN listener is the main attack surface: authenticated before any action, bounded frames and connections, handshake timeout, role/enabled gating per request, no new exposed ports.
- Export only when required (`PlaybackService` for Media3 controllers; `onGetSession` admits only own UID or trusted controllers). New components default to `exported=false`.
- PendingIntents are immutable unless mutability is required and justified.
- Request least privilege at point of need (`RECORD_AUDIO`, `POST_NOTIFICATIONS`, media read) and handle denial and revocation.
- Respect YouTube terms limits documented in `README.md`: no DRM/age/login bypass. GPL-3.0 obligations apply to distributed APKs.

## 14. Build, dependencies, and release

- Preserve plugin and dependency versions, `FAIL_ON_PROJECT_REPOS`, the JitPack content filter (TeamNewPipe only), desugaring, JDK 17. No incidental AGP/Gradle/Kotlin/Compose/Media3/WorkManager/SDK upgrades.
- New dependencies require approval plus license check (GPL compatibility) and a reason no existing API suffices.
- CI (`.github/workflows/homedj-apks.yml`) runs `:app:testDebugUnitTest :app:assembleDebug`; keep it green and do not weaken it.
- Never disable lint/tests/warnings globally; never place secrets in source/resources/Gradle. OAuth client config depends on the signing SHA-1, not on code.
- Release/minify is not configured; enabling R8 is its own Risky task (NewPipe/Rhino/Media3 keep rules need minified verification).

## 15. Tests and debugging

Every behavior-changing feature, logic change, or bug fix gets at least one JVM test that fails without it. Extract pure `internal` functions (like `statusRetryDelayMillis`, `networkForDownload`, `SavedPlaylistCodec`, `RemoteChannel.send/receive`) to test logic without Android. Coverage depth follows §17: Risky assesses every row; Normal covers rows 1 and 10 plus any touched row; Tiny/docs exempt. Missing device proof is `UNVERIFIED`.

```text
1 Core logic: rules, boundaries, invalid/conflicting inputs, outputs.
2 State transitions: role switch, permission grant/revoke, pairing change, expiry.
3 Happy-path E2E: Controller action -> LAN -> Samsung owner -> Media3/file -> observed status.
4 Persistence/process death: reload from DataStore/prefs/JSON/files, not memory.
5 Failure/recovery: Wi-Fi loss, extractor failure, auth unavailable, disk full; then recover.
6 Cross-feature: cast vs playback commands, manual vs auto download, role vs workers.
7 Concurrency/duplicates: rapid taps, overlapping workers, concurrent LAN sessions.
8 UI behavior: controls reflect truth, loading/error honest, masked secrets.
9 Lifecycle/reboot: services, START_STICKY, FGS types, MediaProjection stop, WorkManager.
10 Regression: every fixed bug gets a permanent reproducer test.
```

Debug from evidence: `adb logcat -s HomeDJ HomeDJRemote HomeDJCast HomeDJOffline HomeDJAuto`, `adb shell dumpsys activity services dev.homedj.app`, `adb shell dumpsys jobscheduler | grep homedj`.

## 16. Anti-pattern radar

| Hazard | Safer direction |
|---|---|
| UI calls store/socket/WorkManager | action -> ViewModel/Activity launcher -> owner |
| second player/session or Controller-side playback | `MediaController` -> `PlaybackService` |
| duplicate mutable copies of a fact | one owner + projections |
| public mutable Flow/list | private mutable owner + immutable view |
| `GlobalScope`/unmanaged thread/`runBlocking` | service/ViewModel scope or WorkManager |
| catch-all swallowing cancellation | rethrow `CancellationException` first |
| `delay` used as synchronization | wait for the owner's signal/state |
| controller-trusted IDs or commands | Samsung-side validation in `RemoteLibraryCommands` |
| renamed persisted key/command/work name | read-old/write-new migration + test |
| persisted/logged token, key, URL, account | memory-only, redacted logs |
| unbounded read/retry/queue | explicit caps (frames, bytes, attempts, tracks) |
| fake success after failure | explicit error/stale/unknown state |

## 17. Change tiers and completion

| Tier | Applies to | Done means |
|---|---|---|
| Tiny | copy/strings, comments, docs, styling, rename inside one file | Compiles (code). `Persistence/wire: unchanged`. |
| Normal | logic/state/UI behavior outside §0 hot zones | Tiny + grep proves new code is called and replaced paths have 0 callers + a focused test that fails without the change + `:app:testDebugUnitTest`. |
| Risky | §0 hot zones, persisted/wire contracts, concurrency, services/workers, permissions/manifest, Gradle/CI | Normal + `android-post-change-audit` + applicable §15 rows + CI gate `:app:testDebugUnitTest :app:assembleDebug`. Two-phone/device-only proof is listed as UNVERIFIED. |

Every tier: no parallel truth, UI data access, needless abstraction/upgrade, unrelated cleanup, debug code, secrets, suppressions, or accidental contract changes.

Final report (≤10 lines): changed behavior; owner touched and why; exact commands + results; not verified (device/two-phone); `Persistence/wire: changed/unchanged`; `project.md`/`architecture.md` updated or why not; residual risk.

## 18. Proof of change

Done only when evidence shows intended behavior changed, protected behavior did not, and no material audit finding remains. Compilation is necessary, not sufficient. Evidence must fail if the defect returns (revert and rerun when practical). Verify in proportion to risk: static (diff, reachability, stale paths, contracts) -> deterministic JVM tests (failure, cancellation, duplicate, retry, codec round-trip) -> real boundary (file/codec/crypto) -> device lifecycle (Samsung + OnePlus) -> release variant if configured. Never claim fully verified/production-ready/fixed when material checks were not run or proof is compilation-only.
