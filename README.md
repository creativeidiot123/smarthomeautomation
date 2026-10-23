# HomeDJ

One Android APK with two device-local roles: **Server** on the Samsung Galaxy M21 (Logitech Z623 speakers) and **Controller** on the OnePlus 12. Only the Server owns the Media3 player and downloaded audio; the Controller sends authenticated commands over private Wi-Fi.

## In-app YouTube audio (no Termux)

The Samsung now uses [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) **inside the APK** to find an available, non-DRM audio-only HTTPS stream. No Termux, yt-dlp, FFmpeg, second player or shell execution is needed.

1. Install the same APK on both phones and choose **Server** on the Samsung.
2. In Server mode add YouTube playlists by link (public or unlisted). The app has no Connect YouTube button; no Google account is needed.
3. Select a playlist and tap **Save selected playlist offline**, or paste an accessible playlist URL and tap **Save linked playlist offline**. Individual tracks have **Save** and **Play** actions. The app creates WorkManager downloads directly and deduplicates work by video ID.
4. Downloads are stored as original audio-only M4A/WebM/Opus streams (not converted to MP3) inside the Samsung app's private `files/offline_audio/` directory, with no per-song or total-library size cap (downloads stop if device free space falls below 10 MiB). Each transfer uses a temporary file that becomes playable only after fully downloaded. Tap **Play saved HomeDJ songs (offline)** to queue stored tracks in Media3.
5. Samsung refreshes every saved playlist about **every hour** on unmetered Wi-Fi (Android runs the job inexactly when connectivity and battery allow). Songs newly added to a playlist that already has a downloaded song download automatically; songs removed on YouTube leave the list but keep their audio. Turn on **Auto-save playlists** and mark playlists **Auto-save** to download them in full. **Refresh** on a playlist page (on either phone) does the same for one playlist now. Each pass queues up to 150 songs.

For overnight remote access, use **Samsung → HomeDJ → Settings → Allow background use**. Set HomeDJ's battery use to **Unrestricted** (or allow background activity on older One UI) and add it to **Settings → Battery → Background usage limits → Never sleeping apps**; remove it from Sleeping/Deep sleeping apps. Names vary by One UI version. Keep Samsung powered: remote access holds the CPU awake even while music is paused. The listener restores after unlocked reboot/app update if remote access is still enabled in Server mode. Android force-stop requires opening HomeDJ again. Reserve Samsung's Wi-Fi IP in the router so the OnePlus's saved address stays valid.
6. Downloading a large playlist queues 250 songs at a time and keeps going until every song is downloaded or has failed. Each song is retried 5 times (waits of 1, 2, 4, 8 and 16 minutes, so YouTube's "not a bot" checks can pass); songs that still fail show **Download failed**, and **Download the rest** tries them again.

The **Play** button queues a stable video ID in Media3. When the track starts loading, the service prefers an already downloaded file or resolves an online audio stream. This also works for a remotely queued playlist, one song at a time, so a long queue does not retain stale signed media URLs. It can fail if YouTube changes its private APIs or restricts the media. HomeDJ does not bypass DRM, subscriptions, login restrictions, or age verification. Downloads are for content you are entitled to store; using YouTube audio outside Google's official player may conflict with YouTube terms. This extractor is **not the official YouTube Data API** and Google's read-only OAuth token is never passed into it.

Audio downloads remain after disconnecting YouTube or changing accounts; auto-sync choices and playlist metadata are cleared on disconnect. To remove downloaded files, clear HomeDJ app storage in Android settings (this also removes the saved device role and pairing). The existing **Play older shared Music/HomeDJ files** action supports files previously produced by Termux and needs media-read permission, but **Termux is no longer needed for new music**.

## Remote control

On Samsung, open **Settings**, turn on remote control and tap **Show pairing QR**. On OnePlus, open **Settings → Scan Samsung QR code**, review the filled address/port/code and tap **Pair and connect**. Manual entry uses the same three fields; the port is **47188**. New pairing codes have 26 letters/numbers, shown in groups for easier typing; spaces and lowercase letters are accepted. Existing long codes keep working and can also be scanned. To replace a long code, turn remote control off on Samsung, choose **Create shorter pairing code**, confirm, then turn remote control back on and pair OnePlus again. Install this build on both phones before using a short code. Keep the code and QR private.

Pairing is saved only after Samsung answers successfully and is reused across app restarts, updates and Wi-Fi interruptions. Reserve Samsung's IP in your router to keep the address stable; if it changes, update the address or scan again. Clearing app data or replacing Samsung's code requires pairing again. QR scanning uses Google Play services (its scanner module may need an initial download); manual entry remains available.

Enable **Start remote access** on the Samsung, note the private Wi-Fi address and pairing code, then enter them in **Controller** mode on OnePlus. Remote commands use AES-256-GCM with a server challenge, port 47188, and control the Samsung's sole Media3 session: status, pause/play, skip, shuffle and device volume. The controller does not host or download music. It now browses **Samsung's saved YouTube playlists**, paged track lists and native offline songs over the same encrypted channel. Tap a track to play it on Samsung, tap **Play playlist** to queue up to 2,000 cached tracks in Media3 with just-in-time online/offline audio resolution, or request a native download on Samsung. Saved playlist metadata must first have been synced on Samsung via Google OAuth; OnePlus never receives Google tokens, audio files or the signed media URL. The server sends only titles, IDs and download status. Playlists beyond 2,000 cached tracks require separate handling. Treat the pairing code as sensitive.

## Cast OnePlus app audio to Samsung speakers

1. Start HomeDJ in **Server** mode on Samsung, enable **Start remote access**, and pair your OnePlus in **Controller** mode over the same Wi-Fi.
2. Tap **Start live audio casting** on OnePlus. Grant Android's **Record audio** permission and approve the system screen/audio capture prompt. On Android 14+ you may choose a single app or the whole screen.
3. Play media in a compatible app on OnePlus. HomeDJ forwards 48 kHz stereo PCM over the **existing AES-256-GCM paired channel** on port 47188. Samsung renders it with AudioTrack and its connected speakers, while the normal Media3 queue is paused. The streamed audio is never saved.
4. Tap **Stop live audio casting** or the persistent cast notification's **Stop casting** action. Sending a new Samsung playback command also ends its live stream. A Wi-Fi disconnect ends that session.

This is playback capture, **not** unrestricted system-wide audio mirroring. Android only allows capturing media/game audio from apps that permit it. Many DRM/protected streaming apps, voice calls, microphone audio, apps from other profiles, or apps that block capture cannot be mirrored. HomeDJ sets OnePlus media volume to zero once when a cast starts sending to Samsung. Raising the OnePlus volume afterward is respected; stopping casting leaves the volume as you last set it. Samsung volume remains independent. Raw PCM needs roughly 1.54 Mb/s plus encrypted transport overhead; local Wi-Fi and Samsung device latency determine smoothness and delay. The foreground notification and explicit Android projection consent are mandatory. No media server, transcoding service, cloud relay or additional pairing credential is required.

## Lights, groups and schedules

On either phone's **Lights** tab, use a Matter bulb already connected to the same Wi-Fi with its Matter pairing window open. For a brand-new light that has never been set up, use the **Samsung**: turn on **New light**, allow Bluetooth, enter your 2.4 GHz Wi-Fi name and password, then scan or type its printed Matter code. Samsung finds the light over Bluetooth and gives it your Wi-Fi; HomeDJ remembers the Wi-Fi name and keeps the password only while the app is open, so you can add several lights in a row; the password is never saved or sent to the OnePlus. Paired lights stay paired across restarts and updates installed over the app; uninstalling or clearing HomeDJ's data removes them. Being connected to AiDot alone does not establish a previous Matter pairing. If already commissioned through Matter, open sharing in that Matter controller's app and use its new share code. Use **Scan Wi-Fi**, **Scan QR**, or enter the code. Select a discovered light before scanning/entering its code to check that they match, then confirm **Pair light**. Both phones can add lights and confirm Forget light. OnePlus must be connected to Samsung in Settings; Samsung performs discovery and commissioning, and the setup code is sent only through that encrypted connection. Both phones can control and rename paired lights and edit their groups, preferences and schedules.

Use **Create group** to name a group and select its lights (one group per light). **Edit group** changes its name, members and home/away behaviour. Each bulb's options menu offers **Rename**, **Preferences** and **Add schedule**; groups also have **Add schedule**. Rules choose weekdays and a time in Samsung's local zone and can target all lights, a group or selected bulbs.

The latest change wins between a bulb, its group and all lights. Changing a bulb directly (controls, its preferences, a scene or a selected-bulb schedule) saves its own power/brightness/colour, which then beats group and all-light settings. Changing its group afterwards (group controls, a group schedule, or adding the bulb to a group) clears those own settings so the bulb follows the group again; an all-lights change or all-lights schedule clears them for every bulb, so **All off** turns every light off. Away/home choices stay until cleared. Open the bulb's **Preferences** and choose **Use group settings for everything**, or inherit individual fields separately. Groups provide power, brightness, colour and home/away defaults. Home detection and Remote control must be enabled for presence automation. Install matching builds on both phones for the new priority behaviour.

QR scanning uses Google Play services and may need internet to download its scanner module the first time; manual entry works if it is unavailable. It adds no camera permission. Google documents [on-device code processing](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner) and [ML Kit terms, privacy and SDK metrics](https://developers.google.com/ml-kit/terms); these Google SDK terms apply alongside HomeDJ's existing licensing obligations. HomeDJ keeps setup codes in memory only.

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
- Track save, interrupted transfer, offline-only playback, duplicate enqueue, free-space failure and downloads beyond the former song/library size quotas; persistence across app restart.
- Background download scheduling while idle or rebooted, account change/disconnect, unavailable Google authorization, battery saver and Samsung-specific restrictions.
- Controller pairing/library browsing/streaming, live audio capture authorization, Wi-Fi loss and lifecycle. Android does not guarantee that idle server services or WorkManager jobs run at exact times. Remote playlists without cached metadata need opening/syncing once on Samsung.

## Still not implemented

Device protocols beyond supported Matter Wi-Fi lights, and native DRM/Premium media access.
