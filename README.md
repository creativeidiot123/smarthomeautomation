# HomeDJ

**One Android app, one APK, two selectable roles.**

Install the same APK on both devices. Choose **Server** on the Samsung Galaxy M21 connected to the Logitech Z623 speakers and **Controller** on the OnePlus 12. Each device persists its role independently.

## Current functionality (in-progress milestone)

- Server mode runs one Media3 `MediaLibraryService` player, supports background playback and lock-screen media controls.
- Choose **multiple local audio documents** through Android's document picker. HomeDJ obtains persistable read grants, queues the selected files and offers play, pause, previous, next and shuffle. The active queue is not restored after service process death.
- Server mode offers **read-only YouTube authorization** with Google Play services, lists *playlists owned by the authorized YouTube channel* and lists their video titles. Refresh requires the user's action. The YouTube view and token remain in process memory only; reopening after process death requires reconnecting. Disconnect requests Google revocation when the account is available.
- Controller mode remains explicitly **unpaired**; no remote commands or connection have shipped.

YouTube playlists are **metadata only**. A YouTube video cannot be fed to the local audio player or automatically downloaded by this integration. YouTube API policies prohibit downloading/caching audiovisual content and providing offline playback without YouTube's prior approval. Import local audio that you have rights to use instead.

## Enable YouTube account access

1. Create a project in [Google Cloud Console](https://console.cloud.google.com/), configure the OAuth consent screen and add the `https://www.googleapis.com/auth/youtube.readonly` scope. In testing mode, register test users where required.
2. Enable **YouTube Data API v3** for that Google Cloud project.
3. Create an **Android OAuth client** matching package `dev.homedj.app` and the SHA-1 of the APK's signing certificate. No API key or client secret is embedded in the app. Different debug signing keys have different SHA-1 values.
4. Install an APK signed with the registered certificate on a Google Play services device and tap **Connect YouTube account** in Server mode.

**Signing warning:** GitHub-hosted Actions debug APKs may be signed using an ephemeral debug keystore. For repeatable OAuth testing use a consistent, securely managed signing certificate and register its SHA-1. OAuth and YouTube API integration must be tested on the actual Samsung; CI cannot exercise Google user consent.

Official resources: [Android authorization](https://developer.android.com/identity/authorization), [YouTube playlists.list](https://developers.google.com/youtube/v3/docs/playlists/list), [YouTube playlistItems.list](https://developers.google.com/youtube/v3/docs/playlistItems/list), [YouTube API policies](https://developers.google.com/youtube/terms/developer-policies).

## Build

Install JDK 17 and Android SDK Platform 36. The repository pins Gradle 8.13, AGP 8.11.1 and Kotlin 2.2.20. Run:

```bash
gradle --no-daemon --console=plain :app:testDebugUnitTest :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. The pull-request GitHub workflow builds and uploads the same APK for both devices.

## Physical-device acceptance checks (still required)

1. On Samsung in Server mode, select several authorized local audio files. Verify ordered next/previous, shuffle, audio focus, speaker output, screen-locked playback and continued playback after OnePlus disconnect.
2. Reboot/force-stop and verify that selected role persists while the player queue is not falsely described as restored.
3. Switch to Controller mode and verify that local playback stops. Verify Controller mode still cannot play or remotely command the Samsung.
4. With configured Google OAuth and network access, authorize YouTube, list owned/private playlists, select a playlist, page through its videos, refresh and revoke access. Verify permission refusal, airplane mode, expired token, account revocation and app recreation. YouTube videos should never be loaded into Media3.
5. Test both devices with Android battery restrictions; Android does not guarantee an idle server stays alive.

## Planned, not implemented

Authenticated Wi-Fi pairing, NSD discovery, TLS/WebSockets, remote controls, offline storage and authorized media acquisition, playlist-to-media matching, periodic library sync, schedules, timezone handling, automatic volume, presence automation and reboot recovery. Media playback foreground services are used only during real playback.
