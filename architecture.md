# HomeDJ architecture

## Owners and truth

- **ModeStore**: Preferences DataStore stores the device-local Server/Controller role.
- **PlaybackService**: one Media3 MediaLibraryService/ExoPlayer is the sole playback/queue owner, foreground only during supported media playback; observes the role to stop outside Server mode.
- **ServerPlaybackViewModel**: client projection and commands to the same Media3 session, never another player. Android document-picker URIs are persisted when the user selects external audio.
- **RemoteServerService / RemoteChannel / RemotePrefs / RemoteControllerViewModel**: opt-in AES-GCM authenticated private-LAN protocol. The paired controller pages through server-owned playlists/tracks/downloads and sends verified track/playlist/download commands; it never accesses local audio or Google OAuth tokens directly. RemoteLibraryCommands validates catalog membership before Media3 mutation or WorkManager enqueue.
- **YouTubePlaylistRepository**: read-only Google YouTube Data API for owned/imported playlists, pagination and titles. Authentication token lives only in YouTubeViewModel, not in storage.
- **SavedPlaylistStore**: 4-MB atomic app-private JSON for account-scoped YouTube metadata; serializes screen/Worker writes in-process, erases metadata on explicit disconnect.
- **NativeAudioSource / HomeDjMediaResolver**: NewPipe Extractor v0.26.5 with an application-owned HTTPS Downloader; Media3's ResolvingDataSource resolves stable internal video-ID URIs at load-time (prefer local file, else public non-DRM progressive audio) and caches transient signed links for at most four minutes per player-service instance. The extractor is initialized once, network reads run on Dispatchers.IO, extraction response bodies are size-bounded. Only HTTPS GoogleVideo audio URLs are accepted; no signed URLs are persisted.
- **OfflineAudioLibrary**: owner of app-private files in `files/offline_audio`, keyed by validated 11-character video IDs. WorkManager uses one unique job per ID, atomic `.part` -> final file rename, a 100-MiB per-track and 2-GiB aggregate limit. Completed files are durable; stale partials are cleaned during the next attempt.
- **OfflineAudioWorker**: non-interactive, cancelable, idempotent per-song download. Checks Server role before and throughout the transfer, optional account key/playlist opt-in for automatic downloads, and network/space/size constraints; automated jobs require unmetered connection, manual jobs allow connected; reports failures by safe type and video ID, never signed URLs.
- **AutoLibraryConfig / AutoLibraryWorker**: opt-in device-local settings and unique inexact 12-hour WorkManager job (unmetered + battery-not-low). Silent Google OAuth and account checks precede metadata updates, followed by downloading up to 150 selected tracks through the same native offline WorkManager queue. No Termux command or other-process service lifetime.
- **HomeDjActivity / YouTubeSection / YouTubeViewModel**: user actions, UI state and read-only projections. On-demand stream resolves are Activity-lifecycle-bound; durable downloads belong to WorkManager rather than the UI lifecycle.

## Operational limits

Account metadata is **not** the media source. Google OAuth used for playlist discovery is not supplied to NewPipe; private content, DRM and subscription streams are not unlocked. Audio is directly downloaded only if an unprotected format is exposed by the independent extractor. No third-party stream URLs, account cookies or OAuth values are logged or stored. Shared `Music/HomeDJ` files from earlier Termux versions remain available via MediaStore with Android media-read permission.

Automatic library refresh/worker execution can be deferred or interrupted by Android/One UI; playing already downloaded local audio is independent of connectivity. Paired browsing reads cached metadata only; Google authorization/refresh still happens on Samsung. Remote playlists queue at most 500 cached tracks. Connection loss does not interrupt Samsung playback. Media queues aren't restored after process death. The native download queue is idempotent per video ID, but a user may need to retry a failed download after the extractor changes.

The NewPipe Extractor dependency is GPL-3.0-or-later. Its source is not copied; GPL obligations apply to redistribution. No Room schema or migrations.
