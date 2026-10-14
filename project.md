# HomeDJ project facts

- One installable application module: `:app`, package/application ID `dev.homedj.app`.
- Same debug APK installs on Samsung Galaxy M21 (Server mode) and OnePlus 12 (Controller mode).
- Role selection is stored separately on each device via AndroidX Preferences DataStore 1.1.7.
- Build toolchain: Gradle 8.13, AGP 8.11.1, Kotlin 2.2.20, JDK 17, compileSdk 36, minSdk 29, targetSdk 35.
- Runtime: Compose Material3, Media3 1.8.1.
- No Room database: **Room schema unchanged (not introduced)**.
- Secure remote transport and hardware checks are not implemented; Phase 1 incomplete.
- The inherited root AGENTS.md describes a different Android app (Destination) that is not present here. Its broad engineering invariants apply, not its Destination-specific commands.
