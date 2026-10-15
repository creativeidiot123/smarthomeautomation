# HomeDJ

**One Android app, one APK, two selectable roles.**

Install the same APK on both devices. Choose **Server** on the Samsung Galaxy M21 connected to the Logitech Z623 speakers and **Controller** on the OnePlus 12. Each device persists its role independently.

## Current functionality (in-progress milestone)

- Server mode runs one Media3 `MediaLibraryService` player, supports background playback and lock-screen media controls.
- Choose **multiple local audio documents** through Android's document picker. HomeDJ obtains persistable read grants, queues the selected files and offers play, pause, previous, next and shuffle. The active queue is not restored after service process death.
- Server mode offers **read-only YouTube authorization** with Google Play services, lists *playlists owned by the authorized YouTube channel* and lists their video titles. Refresh requires the user's action. The YouTube view and token remain in process memory only; reopening after process death requires reconnecting. Disconnect requests Google revocation when the account is available.
- You can **paste a YouTube or YouTube Music playlist link** (including a public or accessible unlisted playlist) to browse its tracks even when it isn't owned by your channel. Imported playlist links are in-memory only and disappear on account reconnection/refresh or process restart. Private playlists require access from the signed-in account.
- Server mode can hand a pasted YouTube / YouTube Music playlist URL to **Termux yt-dlp** for an explicit, user-initiated local audio download. HomeDJ does not manage or verify downloads; once Termux completes, manually choose the files with the local audio picker. This optional path works without Google OAuth for playlists that yt-dlp can access. Private/account-restricted content is not automatically authenticated.
- Controller mode can now pair over a private IPv4 Wi-Fi connection using a server IP and a 256-bit pairing code. Requests and responses use AES-256-GCM with a fresh challenge per connection. The server runs an explicitly enabled `connectedDevice` foreground service and relays commands to its existing Media3 session. Controller mode can see current playback, play/pause, skip, toggle shuffle and adjust the Samsung's media volume. The same APK remains installed on both devices. Remote playback requires audio already selected on the server; neither YouTube media streaming nor downloading is implied by pairing.

### Pair Samsung and OnePlus

1. On the Samsung select **Server** and tap **Start remote access**. Keep it on private Wi-Fi with the OnePlus. Allow Android to run its connected-device foreground service.
2. In the server's **Control from OnePlus** card, copy the IPv4 address and the pairing code. The key is sensitive and should only be shared with the other phone.
3. On OnePlus choose **Controller**, enter the server IPv4 and pairing code, and press **Connect**. Successful pairing is saved locally (Android private app data; backup disabled) and is restored next time you open Controller mode.
4. Select local audio on the Samsung, then control its existing queue and volume from OnePlus. When Server mode is switched off, LAN access stops. The remote service is opt-in, never started on Controller mode, and Android may kill it under battery restrictions. Opening Server mode again can restart an enabled service; force-stopping the app cannot be bypassed.

Only private IPv4 addresses are accepted, port `47188` is used, and no pairing code is sent across the socket. AES-GCM encryption authenticates each operation and binds it to a random fresh server challenge. A captured request cannot be replayed in another connection. There is no automatic discovery or QR pairing yet; treat anyone who sees the code as an authorized controller. If compromised, clearing HomeDJ's app data invalidates the key (also clears the device role and saved controller pairing).

The playlist-link import is an independent, official YouTube Data API implementation inspired by [Metrolist](https://github.com/MetrolistGroup/Metrolist), whose GPL-3.0 source is **not copied** into HomeDJ. Metrolist's full YouTube Music streaming/offline architecture uses an unofficial InnerTube client, authenticated sessions, and download/cache infrastructure. Those components are not part of this PR.

The optional Termux workflow downloads files independently of HomeDJ's YouTube Data API integration. Google authorization tokens are **never passed to Termux**. Downloading YouTube content may violate YouTube's terms and may require permission from the rights holder. Use this only where you have the necessary rights; private-use intent does not override platform terms.

## Optional Termux offline audio on Samsung

1. Install a current [Termux](https://github.com/termux/termux-app) build on the Samsung (F-Droid or the Termux project's own APK), then open Termux.
2. Run `pkg update && pkg install python ffmpeg`, followed by `python -m pip install -U yt-dlp` using a supported Termux Python environment. Confirm `yt-dlp --version` succeeds.
3. Run `termux-setup-storage`, grant Termux shared-storage access and enable `allow-external-apps = true` in `~/.termux/termux.properties`. Restart Termux after changing the property.
4. Grant **HomeDJ** Android's **Run commands in Termux environment** permission under app settings (Additional permissions) when prompted. Termux must be installed for the permission to be available.
5. In HomeDJ Server mode paste a YouTube Music playlist link and tap **Download playlist in Termux**. Termux opens a terminal to run yt-dlp and write MP3 files into `Music/HomeDJ/<playlist name>/`. This means only that a command was sent, **not** that the download succeeded. Watch Termux for errors.
6. After completion, choose the audio through **Choose local audio files** in HomeDJ. Automatic import, progress tracking, download resumption, scheduling and remote commands are not yet implemented.

Termux may need battery optimization exceptions on Samsung devices for longer jobs. This version intentionally uses the documented Termux RUN_COMMAND intent and fixed argv, never a shell command assembled from untrusted input. See [Termux RUN_COMMAND integration instructions](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).

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
3. Enable remote access in Server mode, pair Controller via its private Wi-Fi address and secret, and test playback/volume/queue visibility on the Samsung. Test incorrect key, interrupted Wi-Fi, simultaneous commands, paused playback, role switch and service restart. Ensure Controller mode does not create or play any local audio.
4. With configured Google OAuth and network access, authorize YouTube, list owned/private playlists, import a public YouTube Music playlist link, select a playlist, page through its videos, refresh and revoke access. Verify permission refusal, airplane mode, expired token, account revocation and app recreation. YouTube videos should never be loaded into Media3.
5. Test both devices with Android battery restrictions; Android does not guarantee an idle server stays alive.

## Planned, not implemented

Automatic network discovery, QR pairing, HomeDJ-owned offline storage and authorized media acquisition, playlist-to-media matching, periodic library sync, schedules, timezone handling, automatic volume, presence automation and reboot recovery. Media playback foreground services are used only during real playback.
