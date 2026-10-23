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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleResumeEffect
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
    private val pairing: RemotePairingViewModel by viewModels()
    private val schedule: ScheduleViewModel by viewModels()
    private val playlistSort: PlaylistSortViewModel by viewModels()
    private val presence: PresenceViewModel by viewModels()
    private val lights: LightsViewModel by viewModels()
    private val houseRules: HouseRulesViewModel by viewModels()
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
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // The result may contain only notifications when audio was already granted.
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestCastConsent()
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

    /** Samsung playback commands end a live cast there; stop it here first so it is not reported as a Wi-Fi failure. */
    private fun endCastFor(action: String) {
        if (action in setOf("playPause", "next", "previous", "playTrack", "playPlaylist")) {
            LiveAudioCastService.stop(this)
        }
    }

    // OnePlus: foreground permissions, then "Allow all the time". Samsung: Bluetooth only.
    private val presencePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            presence.refresh()
            enablePresence(askedBackground = Manifest.permission.ACCESS_BACKGROUND_LOCATION in results)
        }

    /** Denied permissions leave their signal off; detection still runs on the rest. */
    private fun enablePresence(askedBackground: Boolean? = null) {
        if (roleModel.state.value.mode != HomeDjMode.CONTROLLER) return
        val granted = { permission: String -> checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }
        val missing = presenceControllerPermissions().filterNot(granted)
        val background = Manifest.permission.ACCESS_BACKGROUND_LOCATION
        when {
            askedBackground == null && missing.isNotEmpty() -> presencePermissions.launch(missing.toTypedArray())
            askedBackground != true && granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(background) ->
                presencePermissions.launch(arrayOf(background))
            else -> presence.setEnabled(true)
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (missing: ActivityNotFoundException) {
            downloadMessage("Open Settings > Apps > HomeDJ.")
        }
    }

    /** Samsung new-bulb setup; the ViewModel re-reads the grant and decides at pairing time. */
    private val lightBluetoothRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { lights.bluetoothRequested() }

    private fun allowLightBluetooth() {
        if (roleModel.state.value.mode != HomeDjMode.SERVER) return
        lightBluetoothRequest.launch(lightBluetoothPermissions(Build.VERSION.SDK_INT).toTypedArray())
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
        configureMusicImageLoader()
        setContent {
            val role by roleModel.state.collectAsStateWithLifecycle()
            val pairingState by pairing.state.collectAsStateWithLifecycle()
            val sortState by playlistSort.state.collectAsStateWithLifecycle()
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
                        LifecycleResumeEffect(presence) {
                            presence.refresh()
                            onPauseOrDispose {}
                        }
                        val presenceState by presence.state.collectAsStateWithLifecycle()
                        val nextBreak by MusicBreaks.nextBreak.collectAsStateWithLifecycle()
                        val onePlusSeen by RemoteServerService.onePlus.collectAsStateWithLifecycle()
                        ServerScreen(
                            sortState = sortState,
                            onSort = playlistSort::select,
                            status = status,
                            youtubeState = youtubeState,
                            remoteRunning = remoteRunning,
                            remoteAddress = remoteAddress,
                            remotePairing = pairingState,
                            onReplacePairingCode = pairing::replaceServerCode,
                            onRemoteToggle = ::setRemoteEnabled,
                            batteryUnrestricted = presenceState.batteryUnrestricted,
                            onBackgroundAccess = {
                                if (roleModel.state.value.mode == HomeDjMode.SERVER) openServerBackgroundAccess()
                            },
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
                            onRefreshSelected = youtube::refreshSelectedPlaylist,
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
                            presenceCard = {
                                if (remoteRunning && !presenceState.nearbyGranted) PresenceNearbyCard(onAllow = {
                                    presencePermissions.launch(presenceNearbyPermissions().toTypedArray())
                                })
                            },
                            lightsPage = { LightsPage(remember(youtubeState.playlists) { youtubeState.playlists.associate { it.id to it.title } }) },
                            dashboard = {
                                LiveDashboard({ now, wallNow -> serverDashboard(status, breakSettings,
                                    breakResumesAt, nextBreak, remoteRunning, onePlusSeen, now, wallNow) })
                            },
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
                        LifecycleResumeEffect(presence) {
                            presence.refresh()
                            onPauseOrDispose {}
                        }
                        val presenceState by presence.state.collectAsStateWithLifecycle()
                        RemoteControllerScreen(
                            sortState = sortState,
                            onSort = playlistSort::select,
                            changing = role.changing,
                            state = remoteState,
                            castState = castState,
                            onStartCast = ::requestCast,
                            onStopCast = { LiveAudioCastService.stop(this@HomeDjActivity) },
                            pairing = pairingState,
                            onPairHost = pairing::editHost,
                            onPairPort = pairing::editPort,
                            onPairCode = pairing::editCode,
                            onScanPairing = { scanRemotePairingQr(this@HomeDjActivity, pairing) },
                            onConnect = { pairing.connect(remote::connect) },
                            onCommand = { action, percent ->
                                endCastFor(action)
                                remote.command(action, percent)
                            },
                            onSeek = remote::seek,
                            onDownloadChange = remote::changeDownload,
                            onBrowsePlaylists = remote::browsePlaylists,
                            onBrowseDownloads = remote::browseDownloads,
                            onBrowseTracks = remote::browseTracks,
                            onRetryBrowse = remote::retryBrowse,
                            onLibraryAction = { action, id ->
                                endCastFor(action)
                                remote.libraryAction(action, id)
                            },
                            onImportLink = remote::importPlaylistLink,
                            onRefreshPlaylist = remote::refreshPlaylist,
                            onBreaks = remote::setBreaks,
                            onSwitch = {
                                LiveAudioCastService.stop(this@HomeDjActivity)
                                roleModel.select(HomeDjMode.SERVER)
                            },
                            presenceCard = {
                                PresenceCard(
                                    state = presenceState,
                                    onEnabled = { if (it) enablePresence() else presence.setEnabled(false) },
                                    onSetHome = presence::setHomeHere,
                                    onOpenAppSettings = ::openAppSettings,
                                )
                                val lightsState by lights.state.collectAsStateWithLifecycle()
                                HomeArrivalCard(lightsState,
                                    remember(remoteState.playlists) { remoteState.playlists.associate { it.id to it.title } },
                                    presenceState.autoPause, presence::setAutoPause, lights::setPresence, lights::refresh)
                            },
                            lightsPage = { LightsPage(remember(remoteState.playlists) { remoteState.playlists.associate { it.id to it.title } }) },
                            dashboard = {
                                LiveDashboard({ now, wallNow ->
                                    controllerDashboard(remoteState, presenceState, now, wallNow) })
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

    /**
     * The Lights tab on either phone; Samsung-only actions are hidden on the OnePlus. [playlists]
     * (ID to title) are Samsung's saved playlists a scene can link: its Library, or on the OnePlus the list Samsung sent.
     */
    @Composable
    private fun LightsPage(playlists: Map<String, String>) {
        val state by lights.state.collectAsStateWithLifecycle()
        val actions = remember {
            LightsActions(lights::refresh, lights::pair, lights::change, lights::rename, lights::forget,
                ::requestExactAlarms,
                ::scanLightQr, lights::clearScan, lights::discover, lights::saveGroup, lights::deleteGroup,
                lights::changeGroup, lights::preferences, lights::saveLightRule, lights::removeLightRule,
                lights::enableLightRule, lights::runLightRule, lights::saveScene, lights::deleteScene,
                lights::applyScene, lights::saveLocation, lights::readLight, lights::variation, lights::groupVariation, ::allowLightBluetooth, lights::wifiPassword,
                lights::previewScene, lights::endScenePreview, lights::dance, lights::permanentlyOff)
        }
        val rules by houseRules.state.collectAsStateWithLifecycle()
        val rulesActions = remember { HouseRulesActions(houseRules::refresh, houseRules::save) }
        LightsSection(state, actions, rules, rulesActions, playlists)
    }

    /** Play services owns the camera UI. Codes remain in memory and pairing still needs a tap. */
    private fun scanLightQr() {
        val model = lights
        if (!model.beginScan()) return
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .enableAutoZoom().build()
        try {
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener { model.scanned(it.rawValue) }
                .addOnCanceledListener { model.cancelScan() }
                .addOnFailureListener { model.scannerFailed(it) }
        } catch (failure: Exception) {
            model.scannerFailed(failure)
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

