# HomeDJ project facts

- One Kotlin Android app `:app` (`dev.homedj.app`), minSdk 29, targetSdk 35, compileSdk 36, same APK on Samsung M21 and OnePlus 12.
- Gradle 8.13, AGP 8.11.1, Kotlin 2.2.20, JDK 17; Jetpack Compose/Material 3, Media3 1.8.1, DataStore 1.1.7 and WorkManager 2.11.2.
- Server mode owns one ExoPlayer in MediaLibraryService and all music files; Controller mode owns an AES-GCM paired private-LAN client (port 47188). Role persists per device. No second player.
- YouTube Data API v3 and Android Google AuthorizationClient `youtube.readonly` scope return owned/imported playlist metadata and track IDs. OAuth Android client must match app signing certificate.
- Playlist metadata is persisted app-private via bounded `SavedPlaylistStore` and is account-scoped. Tokens remain in memory; disconnect clears metadata and automatic sync selection.
- **In-app** NewPipeExtractor v0.26.5 (JitPack, GPL-3.0-or-later) independently resolves playable public, non-DRM YouTube audio streams; JDK library desugaring enabled for supported Android devices. No Termux/yt-dlp/FFmpeg needed.
- `OfflineAudioLibrary` owns original audio-only M4A/WebM/Opus app-private downloaded files, WorkManager per-song background jobs, atomic temp-file renames, 100-MiB track cap and 2-GiB aggregate cap. Already downloaded audio works offline via Media3.
- `AutoLibraryWorker` refreshes playlist metadata on inexact unmetered 12-hour work and enqueues up to 150 opted-in track IDs per pass. It does not guarantee timely playback or download completion on Samsung background restrictions.
- The older shared `Music/HomeDJ` MediaStore scan remains optional for legacy audio; it requires READ_MEDIA_AUDIO/READ_EXTERNAL_STORAGE and never invokes Termux. No Room database or schema change.
- GPL obligations require assessment before distributing linked APKs; neither Metrolist source nor InnerTubeX is bundled. YouTube's unofficial extraction can stop working at any time and doesn't unlock restricted content.
- Physical device validation, scheduling, timezone/volume automation, reboot autoplay and full automatic presence control remain incomplete.
