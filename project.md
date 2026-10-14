# HomeDJ project facts

- GitHub repository started with instruction/skill files only; this is the first application source set.
- Modules: `:server` (Android application), `:controller` (Android application), `:shared` (Kotlin JVM).
- Distinct package IDs: `dev.homedj.server` and `dev.homedj.controller`.
- Build toolchain: Gradle 8.13, AGP 8.11.1, Kotlin 2.2.20, JDK 17, compileSdk 36, minSdk 29, targetSdk 35.
- Runtime: Compose Material3, Media3 1.8.1.
- Persistence: none in this first slice; **Room schema: unchanged (not introduced)**.
- Phase 1 incomplete; secure remote transport and hardware validation are not implemented.
- The existing root AGENTS.md describes a different Destination app that is not present in this repository. Its engineering invariants still apply; its module-specific commands are not executable for HomeDJ.
