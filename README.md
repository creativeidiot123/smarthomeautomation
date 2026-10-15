# HomeDJ

One Android APK with two device-local roles: **Server** on the Samsung Galaxy M21 (Logitech Z623 speakers) and **Controller** on the OnePlus 12. Only the Server owns the Media3 player and downloaded audio; the Controller sends authenticated commands over private Wi-Fi.

## In-app YouTube audio (no Termux)

The Samsung now uses [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) **inside the APK** to find an available, non-DRM audio-only HTTPS stream. No Termux, yt-dlp, FFmpeg, second player or shell execution is needed.

1. Install the same APK on both phones and choose **Server** on the Samsung.
2. In Server mode connect a Google account to list its YouTube playlists through the official read-only YouTube Data API. The app needs a registered Android OAuth signing certificate (details below).
3. Select a playlist and tap **Save selected playlist offline**, or paste an accessible playlist URL and tap **Save linked playlist offline**. Individual tracks have **Save** and **Play** actions. The app creates WorkManager downloads directly and deduplicates work by video ID.
4. Downloads are stored as original audio-only M4A/WebM/Opus streams (not converted to MP3) inside the Samsung app's private `files/offline_audio/` directory, capped at 100 MiB per track and 2 GiB in total. Each transfer uses a temporary file that becomes playable only after fully downloaded. Tap **Play saved HomeDJ songs (offline)** to queue stored tracks in Media3.
5. To keep selected playlists updated, opt into **12-hour automatic sync** and select **Auto-download** per playlist. Android runs the periodic job inexactly when unmetered connectivity and battery conditions allow it. The job fetches metadata, queues up to 150 tracks per pass and lets individual WorkManager jobs save them.

The **Play** button queues a stable video ID in Media3. When the track starts loading, the service prefers an already downloaded file or resolves an online audio stream. This also works for a remotely queued playlist, one song at a time, so a long queue does not retain stale signed media URLs. It can fail if YouTube changes its private APIs or restricts the media. HomeDJ does not bypass DRM, subscriptions, login restrictions, or age verification. Downloads are for content you are entitled to store; using YouTube audio outside Google's official player may conflict with YouTube terms. This extractor is **not the official YouTube Data API** and Google's read-only OAuth token is never passed into it.

Audio downloads remain after disconnecting YouTube or changing accounts; auto-sync choices and playlist metadata are cleared on disconnect. To remove downloaded files, clear HomeDJ app storage in Android settings (this also removes the saved device role and pairing). The existing **Play older shared Music/HomeDJ files** action supports files previously produced by Termux and needs media-read permission, but **Termux is no longer needed for new music**.

## Remote control

Enable **Start remote access** on the Samsung, note the private Wi-Fi address and pairing code, then enter them in **Controller** mode on OnePlus. Remote commands use AES-256-GCM with a server challenge, port 47188, and control the Samsung's sole Media3 session: status, pause/play, skip, shuffle and device volume. The controller does not host or download music. It now browses **Samsung's saved YouTube playlists**, paged track lists and native offline songs over the same encrypted channel. Tap a track to play it on Samsung, tap **Play playlist** to queue up to 500 cached tracks in Media3 with just-in-time online/offline audio resolution, or request a native download on Samsung. Saved playlist metadata must first have been synced on Samsung via Google OAuth; OnePlus never receives Google tokens, audio files or the signed media URL. The server sends only titles, IDs and download status. Playlists beyond 500 cached tracks require separate handling. Treat the pairing code as sensitive.

## Google OAuth setup

1. In [Google Cloud Console](https://console.cloud.google.com/), enable **YouTube Data API v3**, configure the consent screen for `https://www.googleapis.com/auth/youtube.readonly`, and add any required test users.
2. Register an Android OAuth client for `dev.homedj.app` matching the **SHA-1 certificate of your APK signing key**. GitHub Actions debug signing may differ from your local build.
3. On Samsung connect in Server mode. Refresh and imported private/unlisted playlists require permission from that account; unavailable metadata cannot be downloaded by HomeDJ.

Tokens are held only in memory. Account-scoped metadata is saved atomically in app-private storage for offline browsing. Silent reauthorization may need user interaction and is never performed interactively by a background worker.

## Build

Uses JDK 17, Gradle 8.13, AGP 8.11.1, Kotlin 2.2.20, Android 29-35 and Media3 1.8.1. NewPipe Extractor `v0.26.5` is pinned via JitPack, with core library desugaring on Android 29-32.

```sh
gradle --no-daemon --console=plain :app:testDebugUnitTest :app:assembleDebug
```

The PR workflow uploads `app/build/outputs/apk/debug/app-debug.apk` as **homedj-debug-apk**.

**License note:** NewPipe Extractor is licensed under GPL-3.0-or-later. Metrolist/InnerTubeX are also GPL-licensed but **their code was not copied**. Anyone distributing HomeDJ APKs linked with NewPipe Extractor must evaluate and comply with GPL source, notice and license obligations. The extractor and transient URL behavior are not guaranteed by either project.

## Physical-device verification still needed

- In-app audio resolution for accessible video IDs, network failures and restricted videos; media focus, resume, speaker output and lock-screen playback.
- Track save, interrupted transfer, offline-only playback, duplicate enqueue, free-space failure and 2-GiB limit; persistence across app restart.
- Background download scheduling while idle or rebooted, account change/disconnect, unavailable Google authorization, battery saver and Samsung-specific restrictions.
- Controller pairing/library browsing/streaming, Wi-Fi loss and lifecycle. Android does not guarantee that idle server services or WorkManager jobs run at exact times. Remote playlists without cached metadata need opening/syncing once on Samsung.

## Still not implemented

Clock-based playback schedules/timezones, automated volume, presence/smart-device automation, reboot autoplay, richer download progress/cancel UI and native DRM/Premium media access.
