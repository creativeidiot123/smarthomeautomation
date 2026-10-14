# HomeDJ

Two **native Android apps** in one Gradle project:

- `:server`: Samsung Galaxy M21, owns Media3 playback and audio output.
- `:controller`: OnePlus 12, will control the server over an authenticated, pinned-TLS local connection.
- `:shared`: shared Kotlin models/contracts.

**Current milestone:** Phase 1 foundation, **NOT Phase 1 complete**. The server can choose an authorized local audio document and play it with Media3 (subject to device testing). Android owns its media controls and playback foreground service. The controller builds and displays an honest unpaired state. There is no remote control, discovery or pairing yet, and no public control socket.

## Build

Requirements: JDK 17, Android SDK Platform 36, Android Gradle plugin 8.11.1, Kotlin 2.2.20, Gradle **8.13**.

```bash
gradle --no-daemon --console=plain :shared:test :server:assembleDebug :controller:assembleDebug
```

Outputs:

- `server/build/outputs/apk/debug/server-debug.apk`
- `controller/build/outputs/apk/debug/controller-debug.apk`

The pull-request GitHub Actions workflow performs this build on the repository's Linux self-hosted runner and uploads both debug APKs. These builds use debug signing and are intended for local testing, not production distribution.

## Samsung smoke check (hardware validation pending)

1. Install `server-debug.apk` on the Galaxy M21, connect speakers to Samsung's analog audio output.
2. Open **HomeDJ Server**, tap **Choose local audio** and grant access to a file you own.
3. Confirm sound on the Logitech speakers; try play/pause and lock-screen/hardware controls.
4. Lock the Samsung, leave the OnePlus disconnected, and check that audio continues.
5. Unplug audio or induce audio focus loss; verify pause/noisy-audio handling.

This is a manual acceptance checklist, **not an assertion that any device has been tested**.

## Release gates

Do **not** merge this as a completed Phase 1. The next Phase 1 slice must implement: NSD discovery, QR pairing, certificate-pinned TLS, authenticated HTTP commands and WebSocket updates, reconnect and idempotent command handling, Android 17 local-network permission, and real-phone tests. All other phases remain out of scope.

No service is used to keep unrelated background automation continuously alive. Server availability while idle depends on documented Android platform restrictions.
