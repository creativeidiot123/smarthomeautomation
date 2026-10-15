# HomeDJ project facts

- One installable application module: `:app`, package/application ID `dev.homedj.app`.
- Same APK installs on Samsung Galaxy M21 (Server mode) and OnePlus 12 (Controller mode).
- Role selection is stored separately on each device via AndroidX Preferences DataStore 1.1.7.
- Build toolchain: Gradle 8.13, AGP 8.11.1, Kotlin 2.2.20, JDK 17, compileSdk 36, minSdk 29, targetSdk 35.
- Runtime: Compose Material3, Media3 1.8.1, Google Play services auth 21.6.0; internet permission for YouTube metadata API.
- Server mode offers a multiple-document audio picker, a transient Media3 queue and shuffle. Android persisted URI read grants allow files to remain accessible but Media3's queue is not restored after process death.
- YouTube playlists and tracks metadata: Android Google AuthorizationClient with `youtube.readonly` scope, YouTube Data API v3 `playlists.list(mine=true)` and `playlistItems.list`. Google OAuth configuration in Cloud Console and matching Android signing SHA-1 are prerequisites for use. Does not access followed/other channels' playlists, automatically sync or download YouTube video/audio.
- No YouTube token or metadata persistence; authorization and browsing state are memory-only. Disconnect requests Google access revocation when the authorized account is available.
- No Room database: **Room schema unchanged (not introduced)**.
- Secure remote transport and hardware checks are not implemented; the LAN pairing phase remains incomplete.
- The inherited root AGENTS.md describes a different Android app (Destination) that is not present here. Its broad engineering invariants apply, not its Destination-specific commands.
