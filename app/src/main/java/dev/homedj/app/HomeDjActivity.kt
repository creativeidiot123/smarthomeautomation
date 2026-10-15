package dev.homedj.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.content.ActivityNotFoundException
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope

class HomeDjActivity : ComponentActivity() {
    private val roleModel: RoleViewModel by viewModels()
    private val playback: ServerPlaybackViewModel by viewModels()
    private val youtube: YouTubeViewModel by viewModels()
    private val remote: RemoteControllerViewModel by viewModels()
    private val schedule: ScheduleViewModel by viewModels()
    private var authorizationPending = false
    private var revokePending = false
    private val scopes = listOf(Scope(YOUTUBE_SCOPE))
    private val downloadReadPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && roleModel.state.value.mode == HomeDjMode.SERVER) queueDownloadedAudio()
            else if (!granted) downloadMessage("Allow music access to play downloaded audio.")
        }

    private val audioPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (roleModel.state.value.mode != HomeDjMode.SERVER) return@registerForActivityResult
            val selected = uris.distinct().mapNotNull { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    LocalAudio(uri, displayName(uri))
                } catch (exception: SecurityException) {
                    Log.w("HomeDJ", "Unable to persist read grant for local audio")
                    null
                }
            }
            if (selected.size < uris.distinct().size) {
                Toast.makeText(this, "Some audio files could not be accessed", Toast.LENGTH_SHORT).show()
            }
            if (selected.isNotEmpty()) playback.playPickedAudio(selected)
        }

    // Notification access is optional; without it Android 13+ hides the cast's Stop action.
    private val castPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.RECORD_AUDIO] == true) requestCastConsent()
            else downloadMessage("Audio capture permission is required to cast permitted app playback.")
        }

    private val castConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val grant = result.data
            if (result.resultCode == Activity.RESULT_OK && grant != null &&
                roleModel.state.value.mode == HomeDjMode.CONTROLLER
            ) {
                try {
                    LiveAudioCastService.start(this, result.resultCode, grant)
                } catch (error: Exception) {
                    Log.w("HomeDJCast", "Cast service start failed: " + error.javaClass.simpleName)
                    downloadMessage("Could not start audio casting.")
                }
            }
        }

    private val exactAlarmSettings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { schedule.refreshPermission() }

    private fun requestExactAlarms() {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            exactAlarmSettings.launch(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", packageName, null)))
        } catch (missing: ActivityNotFoundException) {
            downloadMessage("Allow HomeDJ under Settings > Apps > Special access > Alarms & reminders.")
        }
    }

    private fun requestCast() {
        if (roleModel.state.value.mode != HomeDjMode.CONTROLLER || !remote.state.value.connected) return
        val missing = listOfNotNull(
            Manifest.permission.RECORD_AUDIO,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) requestCastConsent() else castPermission.launch(missing.toTypedArray())
    }

    private fun requestCastConsent() {
        if (roleModel.state.value.mode != HomeDjMode.CONTROLLER || !remote.state.value.connected) return
        val projection = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        castConsent.launch(projection.createScreenCaptureIntent())
    }

    private val authorizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            authorizationPending = false
            if (roleModel.state.value.mode != HomeDjMode.SERVER) return@registerForActivityResult
            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                youtube.authorizationError("YouTube access was not granted")
                return@registerForActivityResult
            }
            try {
                useAuthorization(Identity.getAuthorizationClient(this)
                    .getAuthorizationResultFromIntent(result.data))
            } catch (exception: Exception) {
                Log.w("HomeDJ", "Google authorization result failed: " + exception.javaClass.simpleName)
                youtube.authorizationError("Could not connect your YouTube account")
            }
        }

    private fun requestDownloadedAudio() {
        if (roleModel.state.value.mode != HomeDjMode.SERVER) return
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            queueDownloadedAudio()
        } else {
            downloadReadPermission.launch(permission)
        }
    }

    private fun queueDownloadedAudio() {
        lifecycleScope.launch {
            try {
                val audio = withContext(Dispatchers.IO) {
                    DownloadedAudioLibrary.scan(this@HomeDjActivity)
                }
                if (roleModel.state.value.mode != HomeDjMode.SERVER) return@launch
                if (audio.isEmpty()) {
                    downloadMessage("No previously downloaded shared audio found in Music/HomeDJ.")
                } else {
                    playback.playPickedAudio(audio)
                    downloadMessage("Queued " + audio.size + " downloaded audio files.")
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                Log.w("HomeDJ", "MediaStore HomeDJ audio scan failed: " + failure.javaClass.simpleName)
                downloadMessage("Unable to read downloaded audio. Check storage permission.")
            }
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Selected audio"

    private fun requestYouTubeAccess(interactive: Boolean = true) {
        if (authorizationPending || revokePending || roleModel.state.value.mode != HomeDjMode.SERVER) return
        authorizationPending = true
        val request = AuthorizationRequest.builder().setRequestedScopes(scopes).build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener(this) { result ->
                if (roleModel.state.value.mode != HomeDjMode.SERVER) {
                    authorizationPending = false
                } else if (result.hasResolution()) {
                    if (!interactive) {
                        authorizationPending = false
                        return@addOnSuccessListener
                    }
                    val pending = result.pendingIntent
                    if (pending == null) {
                        authorizationPending = false
                        youtube.authorizationError("Google authorization is unavailable")
                    } else {
                        try {
                            authorizationLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                        } catch (failure: Exception) {
                            authorizationPending = false
                            Log.w("HomeDJ", "Unable to launch Google authorization")
                            youtube.authorizationError("Unable to start Google authorization")
                        }
                    }
                } else {
                    authorizationPending = false
                    useAuthorization(result)
                }
            }
            .addOnFailureListener(this) { failure ->
                authorizationPending = false
                Log.w("HomeDJ", "Google authorization failed: " + failure.javaClass.simpleName)
                if (interactive) {
                    youtube.authorizationError("YouTube authorization failed. Check Google Play services.")
                }
            }
    }

    @Suppress("DEPRECATION")
    private fun useAuthorization(result: AuthorizationResult) {
        val token = result.accessToken
        if (roleModel.state.value.mode != HomeDjMode.SERVER) return
        if (token.isNullOrBlank() || YOUTUBE_SCOPE !in result.grantedScopes) {
            youtube.authorizationError("Google did not grant read-only YouTube access")
            return
        }
        youtube.authorized(token, result.toGoogleSignInAccount()?.account)
    }

    private fun setRemoteEnabled(enabled: Boolean) {
        try {
            RemoteServerService.setEnabled(this, enabled)
        } catch (failure: Exception) {
            Log.e("HomeDJRemote", "Could not change remote service state", failure)
            downloadMessage("Unable to start remote access. Check Android foreground permissions.")
        }
    }

    private fun downloadMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Queues the shown playlist from the tapped song, so next and previous move through it. */
    private fun playYouTubeTrack(playlist: List<YouTubeTrack>, track: YouTubeTrack) {
        if (roleModel.state.value.mode != HomeDjMode.SERVER) return
        val queue = playlistQueue(playlist, track.id) ?: return
        // PlaybackService resolves each stream when its item loads; failures surface as player errors.
        playback.playTracks(queue.tracks, queue.start)
    }

    private fun disconnectYouTube() {
        if (revokePending || authorizationPending) return
        val account = youtube.authorizedAccount
        if (account == null) {
            youtube.disconnectAndEraseCatalog()
            youtube.authorizationError(
                "Playlist view cleared. Revoke the app's access from Google Account settings."
            )
            return
        }
        revokePending = true
        val request = RevokeAccessRequest.builder().setAccount(account).setScopes(scopes).build()
        Identity.getAuthorizationClient(this).revokeAccess(request)
            .addOnSuccessListener(this) {
                revokePending = false
                youtube.disconnectAndEraseCatalog()
            }
            .addOnFailureListener(this) { failure ->
                revokePending = false
                Log.w("HomeDJ", "Google revoke failed: " + failure.javaClass.simpleName)
                youtube.authorizationError("Could not revoke Google access. Please try again.")
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val role by roleModel.state.collectAsStateWithLifecycle()
            HomeDjTheme {
                when {
                    !role.loaded -> RoleLoadingScreen(role.error)
                    role.mode == HomeDjMode.SERVER -> {
                        DisposableEffect(playback, youtube) {
                            playback.connect()
                            youtube.showSavedCatalog()
                            AutoLibraryConfig(this@HomeDjActivity).schedule()
                            // ViewModels survive rotation; keep their token and selection.
                            if (!youtube.state.value.authorized) requestYouTubeAccess(interactive = false)
                            if (RemotePrefs(this@HomeDjActivity).enabled) setRemoteEnabled(true)
                            onDispose {
                                if (!isChangingConfigurations) {
                                    playback.disconnect()
                                    youtube.clear()
                                }
                            }
                        }
                        val status by playback.status.collectAsStateWithLifecycle()
                        val youtubeState by youtube.state.collectAsStateWithLifecycle()
                        val scheduleState by schedule.state.collectAsStateWithLifecycle()
                        val breakSettings by playback.breakSettings.collectAsStateWithLifecycle()
                        val breakResumesAt by playback.breakResumesAt.collectAsStateWithLifecycle()
                        // Cached song counts change whenever Library syncs playlists or their songs.
                        LaunchedEffect(youtubeState.playlists, youtubeState.tracks) { schedule.load() }
                        val remoteRunning by RemoteServerService.running.collectAsStateWithLifecycle()
                        val activity by RemoteActivity.entries.collectAsStateWithLifecycle()
                        val remoteAddress = remember(remoteRunning) { RemotePrefs.localAddress(this@HomeDjActivity) }
                        val pairingCode = remember { RemotePrefs(this@HomeDjActivity).serverKey() }
                        ServerScreen(
                            status = status,
                            youtubeState = youtubeState,
                            remoteRunning = remoteRunning,
                            remoteAddress = remoteAddress,
                            remotePairingCode = pairingCode,
                            onRemoteToggle = ::setRemoteEnabled,
                            changing = role.changing,
                            onPickAudio = { audioPicker.launch(arrayOf("audio/*")) },
                            onPlaySharedDownloads = ::requestDownloadedAudio,
                            onPlayPause = playback::playOrPause,
                            onNext = playback::next,
                            onPrevious = playback::previous,
                            onShuffle = playback::toggleShuffle,
                            onSeek = playback::seekTo,
                            onConnectYouTube = { requestYouTubeAccess(interactive = true) },
                            onDisconnectYouTube = ::disconnectYouTube,
                            onSelectPlaylist = youtube::selectPlaylist,
                            onImportPlaylist = youtube::importPlaylist,
                            onDownloadPlaylist = youtube::downloadPlaylistLink,
                            onStreamTrack = { playYouTubeTrack(youtubeState.tracks, it) },
                            onPlayTracks = { tracks, start ->
                                tracks.getOrNull(start)?.let { playYouTubeTrack(tracks, it) }
                            },
                            onDownloadTrack = youtube::saveTrackOffline,
                            onDownloadSelected = youtube::saveSelectedOffline,
                            onCancelSaves = youtube::cancelSaves,
                            onRemoveDownloads = youtube::removeDownloads,
                            onAutoEnabled = youtube::setAutoEnabled,
                            onToggleAutoPlaylist = youtube::toggleAutoPlaylist,
                            onSwitch = { roleModel.select(HomeDjMode.CONTROLLER) },
                            scheduleState = scheduleState,
                            onResumeSchedule = playback::resumeSchedule,
                            onJumpTo = playback::jumpTo,
                            onPlayNext = playback::playNext,
                            onAddToQueue = playback::addToQueue,
                            onSaveSlot = schedule::save,
                            onDeleteSlot = schedule::delete,
                            onAllowExactAlarms = ::requestExactAlarms,
                            breakSettings = breakSettings,
                            breakResumesAt = breakResumesAt,
                            onBreaks = playback::setBreaks,
                            activity = activity,
                        )
                    }
                    role.mode == HomeDjMode.CONTROLLER -> {
                        // Poll Samsung only while visible; returning to the app re-polls at once
                        // instead of waiting out a backoff that grew while the app was in the background.
                        LifecycleStartEffect(remote) {
                            remote.start()
                            onStopOrDispose { remote.stop() }
                        }
                        val remoteState by remote.state.collectAsStateWithLifecycle()
                        val castState by LiveAudioCastService.state.collectAsStateWithLifecycle()
                        RemoteControllerScreen(
                            changing = role.changing,
                            state = remoteState,
                            castState = castState,
                            onStartCast = ::requestCast,
                            onStopCast = { LiveAudioCastService.stop(this@HomeDjActivity) },
                            onConnect = remote::connect,
                            onCommand = remote::command,
                            onSeek = remote::seek,
                            onDownloadChange = remote::changeDownload,
                            onBrowsePlaylists = remote::browsePlaylists,
                            onBrowseDownloads = remote::browseDownloads,
                            onBrowseTracks = remote::browseTracks,
                            onLoadMore = remote::loadMore,
                            onLibraryAction = remote::libraryAction,
                            onImportLink = remote::importPlaylistLink,
                            onBreaks = remote::setBreaks,
                            onSwitch = {
                                LiveAudioCastService.stop(this@HomeDjActivity)
                                roleModel.select(HomeDjMode.SERVER)
                            },
                        )
                    }
                    else -> RolePicker(
                        changing = role.changing,
                        onServer = { roleModel.select(HomeDjMode.SERVER) },
                        onController = { roleModel.select(HomeDjMode.CONTROLLER) },
                    )
                }
            }
        }
    }

    override fun onStop() {
        // Activity-scoped Play services listeners are dropped at onStop; never leave a request stuck.
        authorizationPending = false
        revokePending = false
        super.onStop()
    }

    private companion object {
        const val YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
    }
}

