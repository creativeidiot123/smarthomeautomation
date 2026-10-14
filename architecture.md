# HomeDJ architecture (single APK, Phase 1 foundation)

## Ownership

- `ModeStore`: **only persisted owner** of each device's selected role. It exposes a DataStore `Flow<HomeDjMode?>`. Null means no valid role selected, never an implicit Server mode.
- `RoleViewModel`: lifecycle-owned projection/loading/error state for role changes. Double selection is first-wins until persisted. Switching roles is reversible and does not transfer server state to another phone.
- `PlaybackService : MediaLibraryService`: sole ExoPlayer, queue, audio focus, session, foreground playback owner. Collects role preferences for its own lifetime and stops/clears playback in non-Server roles. It accepts platform-trusted/local media controllers only, not arbitrary other apps. Playback is not started by choosing Server mode.
- `ServerPlaybackViewModel`: read-only playback projection and commands to Media3 session. Connects only while Server UI is composed; disconnects on mode switch or screen disposal, leaving Media3 foreground playback active when appropriate.
- `HomeDjActivity`: system document picker, Android URI grant and Compose host. Controller mode has no local player or fake remote control. Role and playback state are presented from their owners.

## Trust and lifecycle

No network listener exists, so no plaintext commands, remote pairing keys or untrusted remote execution exists. Local Media3 service connections are checked for system trust; future LAN pairing will separately enforce authenticated and encrypted remote requests.

Role change Server -> Controller is serialized by DataStore; the server service observes the persisted role and stops/clears its queue. On error persisting a new role, the original role stays active and the UI reports failure. If the process is killed before a preference write commits, the previous role remains authoritative on restart.

No service is used to keep unrelated automation alive. Playback while locked depends on Media3 foreground policy and actual device battery restrictions; hardware tests remain pending. Media3 playback queue persistence/process restore is not yet implemented.

## Next Phase 1 boundary

NSD discovery, QR pairing, certificate-pinned TLS, authenticated command semantics and WebSocket projection must feed this same Media3 session. No second audio engine in Controller mode. Cross-device commands remain disabled until their authentication is enforceable.
