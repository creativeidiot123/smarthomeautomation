# HomeDJ

**One Android app, one APK, two selectable roles.**

Install the same `app-debug.apk` on both phones. Pick **Server** on the Samsung Galaxy M21 connected to the Logitech Z623 speakers, and **Controller** on the OnePlus 12. Each phone saves its own selected role using AndroidX Preferences DataStore; roles can be switched in the app.

**Current milestone: Phase 1 foundation, not Phase 1 complete.** Server mode provides Media3 local playback of one user-selected audio document and Android media controls. Controller mode currently displays an explicitly unpaired state. No LAN listener, QR pairing, remote commands, or WebSocket connections have shipped. Switching to Controller mode tells the server Media3 service to stop and clear local playback.

## Build

Install JDK 17, Android SDK Platform 36, Gradle 8.13, and use the pinned project plugins (AGP 8.11.1, Kotlin 2.2.20).

```bash
gradle --no-daemon --console=plain :app:testDebugUnitTest :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. The PR workflow runs on the Linux self-hosted runner and uploads the one debug APK for installation on *both* phones.

## Samsung smoke test (not yet performed)

1. Install the APK, choose **Server** and connect Logitech speakers to Samsung's audio output.
2. Tap **Choose local audio**; authorize access to an audio file you own.
3. Confirm audio output, pause/resume, lock-screen media controls and playback while the OnePlus is disconnected.
4. Verify disconnecting wired headphones pauses playback as appropriate.
5. Select Controller mode and confirm playback stops, then reopen app and verify chosen role persists.

## OnePlus smoke test (not yet performed)

1. Install the *same* APK and choose **Controller**.
2. Confirm the UI says **Not paired** and does not play local music.
3. Restart the app and verify it remains in Controller mode.

## Remaining Phase 1 work

Implement authenticated QR pairing, NSD service discovery, pinned TLS, local HTTP commands, WebSockets, replay-safe command IDs, and Android 17 local-network permission handling. Validate on the two physical phones including screen-locked playback, Wi-Fi changes and client isolation. Later phases cover library, downloads, schedules, presence and devices.

Media playback foreground services are only for actual playback. Android may restrict process survival when idle; HomeDJ does not promise always-on hosting on stock Android.
