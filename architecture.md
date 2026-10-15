# HomeDJ architecture (single APK, local library and YouTube read-only browsing)

## Ownership

- `ModeStore`: **only persisted owner** of each device's selected role. DataStore exposes `Flow<HomeDjMode?>`; null means no valid role selected. Switching roles is reversible and does not transfer server state.
- `RoleViewModel`: lifecycle-owned role loading/error state. Role changes are first-wins while DataStore saves.
- `PlaybackService : MediaLibraryService`: sole ExoPlayer, audio focus, playback queue, session and foreground-playback owner. It watches the role and stops/clears its queue outside Server mode. Trust checks remain for local Media3 controllers; no remote peer may control it.
- `ServerPlaybackViewModel`: read-only player projection and commands to the session. Connects only in Server UI; selection produces a new local audio queue, not a second player. Android's SAF document picker and persisted URI permissions are owned by `HomeDjActivity`. The Media3 queue is transient after process death.
- `YouTubePlaylistLinks`: pure, strict conversion of pasted HTTPS YouTube Music / YouTube playlist URLs to a safe playlist ID; never allows user-supplied destinations for API calls.
- `YouTubePlaylistRepository`: read-only, HTTPS YouTube Data API boundary. Maps both account-owned and explicitly imported (ID-based) playlists and playlist item responses, follows distinct pagination tokens (max 100 pages), enforces timeouts and exposes sanitized errors; no file/video downloads. The Google account is the upstream authority; the network is the only source. No persisted cache or Room owner exists.
- `YouTubeViewModel`: only in-memory owner of a short-lived access token and account reference, and screen projection for returned metadata. Latest owned-list refresh or link import cancels prior playlist fetch and track query; selection cancels prior track query. Imported playlists live only in current UI state and are discarded when the account is reauthorized or the view resets. All playlist data is discarded on server-screen disposal, disconnect or process death. Requests are restarted explicitly after reconnecting.
- `TermuxPlaylistDownloads`: validates fixed yt-dlp command arguments for a playlist ID and starts the explicit Termux RUN_COMMAND service; Termux owns execution/storage, and HomeDJ receives no completion status. No Google tokens or raw user-provided URLs are sent.
- `HomeDjActivity`: system audio picker, user-tapped Termux permission/download handoff, and Android Google authorization/revocation launchers; composes read-only UI state. Consent may require an Android IntentSender resolution. The token is passed to the ViewModel only after the YouTube read-only scope has been granted. Account association for revocation uses Google's deprecated account accessor while available; if unavailable the app clears local state and instructs user to revoke from Google Account settings.

## Trust, privacy and lifecycle

Google access tokens are sent only in Authorization headers to a fixed HTTPS Google API host. The optional Termux command uses solely a validated playlist ID; it does not receive Google credentials, account data, or HomeDJ playback authority. Tokens, account IDs, playlist titles and response bodies are not logged or persisted. API errors are shown as safe user-facing messages. There is no custom backend, stored refresh token, background OAuth or background YouTube polling. The server keeps handling local playback even when the YouTube view is absent.

Switching Server to Controller is serialized via DataStore; the service observes the persisted role and stops its queue, and the Server UI disconnects its MediaController and clears its YouTube state. On failure persisting a new role, the original mode remains active. Android may kill an idle process; neither always-on remote hosting nor queue restoration is guaranteed.

Google Play services and an OAuth Android app registration for this APK's signing key are mandatory for authenticated YouTube browsing. The optional Termux handoff is independent of Google OAuth, user initiated and requires Termux permission and local setup. Without them, local audio playback still works. The YouTube Data API metadata is not a playback/download source, and the independent Termux handoff cannot be used as proof of download success. A real-device OAuth, network and media lifecycle test remains required.

## Next boundary

Remote access requires NSD discovery, pairing, TLS and replay-safe authenticated commands feeding the **existing** Media3 session. Schedules, offline storage, volume policies, persistent queue and startup/reboot behavior require their own server-owned truth and platform-specific tests. No controller playback engine or duplicate scheduling authority is to be introduced.
