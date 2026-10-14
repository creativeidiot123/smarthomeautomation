# HomeDJ architecture (Phase 1 foundation)

## Authority and lifecycle

`PlaybackService : MediaLibraryService` owns the sole Media3 ExoPlayer, queue, audio focus and playback truth. The OS starts its media-playback foreground state when required by Media3. It releases its session/player in `onDestroy`.

`ServerPlaybackViewModel` asynchronously connects via a Media3 `MediaController` and observes `Player.Listener`. It exposes a read-only `StateFlow<ServerPlaybackUiState>`; mutations are dispatched back to the session. On ViewModel disposal it removes its listener and releases the controller future. Compose only renders the state and dispatches callbacks.

The Samsung activity uses Android's `OpenDocument` contract. The chosen content URI stays within the same app; the caller requests a persistent read grant before submitting the item. No storage enumeration/permission and no arbitrary file path are used. Playback is triggered only by a user action.

`PlaybackSnapshot` is a shared read-only projection, not an independent mutable player.

## Security and background constraints

Only the platform/local Media3 session is exposed. No LAN listener, authentication database, download runner, smart device worker, or general-purpose service has been created. Both applications disable backup and cleartext traffic. QR pairing and pinned TLS must be implemented before LAN commands are enabled.

Media3 can continue foreground playback with the UI gone, but device power management and background-start restrictions remain. Hardware/device tests are pending. No library/database schema exists and this commit does not infer playback recovery across process death.

## Next boundary

Server-owned HTTPS transport and authenticated WebSocket state will project the same Media3 session into the OnePlus UI. NSD will advertise only when a listening socket actually exists. Timeouts and retries must be modeled with command IDs/server deduplication before remote mutations ship.
