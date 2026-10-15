package dev.homedj.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Opt-in foreground LAN server. It owns only the encrypted endpoint; Media3 PlaybackService
 * remains the sole owner of playback and queue. No socket is ever exposed as an Android binder.
 */
class RemoteServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var mediaFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val musicLibrary by lazy { RemoteLibraryCommands(applicationContext) }
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val connections = Semaphore(6)
    private val castSocket = AtomicReference<Socket?>(null)
    private var wifiLock: WifiManager.WifiLock? = null

    private fun stopCast() {
        castSocket.getAndSet(null)?.let {
            it.close()
            RemoteActivity.record(RemoteActivity.ONEPLUS, "Live audio stopped")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Android may recreate an opted-in remote listener after process death.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("remote", "HomeDJ remote control", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, "remote")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("HomeDJ remote control")
            .setContentText("Accepting paired connections on local Wi-Fi")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(42, notification)
            }
        } catch (failure: Exception) {
            Log.e("HomeDJRemote", "Could not start remote foreground service", failure)
            stopSelf()
            return
        }
        // Wi-Fi power save drops or delays inbound packets while the screen is off, which shows
        // up on OnePlus as connect timeouts. Android 14+ silently downgrades this to screen-on only.
        try {
            @Suppress("DEPRECATION")
            val lock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HomeDJ:remote")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (failure: RuntimeException) {
            Log.w("HomeDJRemote", "Wi-Fi lock unavailable: " + failure.javaClass.simpleName)
        }
        val future = MediaController.Builder(
            this, SessionToken(this, ComponentName(this, PlaybackService::class.java))
        ).buildAsync()
        mediaFuture = future
        future.addListener({
            try {
                controller = future.get()
            } catch (failure: Exception) {
                Log.e("HomeDJRemote", "Remote Media3 session connection failed", failure)
            }
        }, mainExecutor)
        scope.launch {
            ModeStore(this@RemoteServerService).mode.collect {
                if (it != HomeDjMode.SERVER || !RemotePrefs(this@RemoteServerService).enabled) {
                    stopSelf()
                }
            }
        }
        scope.launch { listen() }
    }

    private suspend fun listen() {
        try {
            val listener = ServerSocket(47188)
            server = listener
            running.value = true
            val key = requireNotNull(RemoteChannel.decodeKey(RemotePrefs(this).serverKey()))
            while (true) {
                val client = listener.accept()
                if (!connections.tryAcquire()) {
                    client.close()
                    continue
                }
                clients.add(client)
                // soTimeout bounds each read, not a slowly trickled request; cap the whole handshake.
                val handshake = scope.launch { delay(HANDSHAKE_MILLIS); client.close() }
                scope.launch {
                    try {
                        client.use { socket ->
                            socket.soTimeout = 5_000
                            socket.tcpNoDelay = true
                            RemoteChannel.serve(
                                DataInputStream(socket.getInputStream()),
                                DataOutputStream(socket.getOutputStream()),
                                key,
                                action = { request -> handshake.cancel(); handle(request, socket) },
                                onPcm = { next -> LiveAudioSink.play(this@RemoteServerService, next) },
                            )
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        Log.w("HomeDJRemote", "LAN session ended: " + failure.javaClass.simpleName)
                    } finally {
                        handshake.cancel()
                        if (castSocket.compareAndSet(client, null)) {
                            RemoteActivity.record(RemoteActivity.ONEPLUS, "Live audio ended")
                        }
                        clients.remove(client)
                        connections.release()
                    }
                }
            }
        } catch (failure: SocketException) {
            if (scope.coroutineContext[Job]?.isActive == true) {
                Log.e("HomeDJRemote", "LAN listener socket error", failure)
                stopSelf()
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Log.e("HomeDJRemote", "LAN listener could not start", failure)
            stopSelf()
        } finally {
            running.value = false
            server?.close()
            server = null
        }
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private suspend fun handle(request: JSONObject, socket: Socket): JSONObject =
        withContext(Dispatchers.Main.immediate) {
            if (!RemotePrefs(this@RemoteServerService).enabled ||
                ModeStore(this@RemoteServerService).mode.first() != HomeDjMode.SERVER
            ) {
                return@withContext JSONObject().put("ok", false).put("error", "Server role inactive")
            }
            val active = controller ?: return@withContext JSONObject()
                .put("ok", false).put("error", "Playback service not ready")
            val action = request.optString("command")
            if (action == "audioStart") {
                if (!castSocket.compareAndSet(null, socket)) {
                    return@withContext JSONObject().put("ok", false).put("error", "Another cast is running")
                }
                active.pause()
                RemoteActivity.record(RemoteActivity.ONEPLUS, "Live audio started")
                return@withContext JSONObject().put("ok", true)
            }
            // Newer controllers send the index they showed; a skip aimed at an older song is refused.
            if (action in setOf("next", "previous") && isStaleSkip(
                    if (request.has("index")) request.optInt("index") else null, active.currentMediaItemIndex)
            ) {
                return@withContext JSONObject().put("ok", false).put("error", STALE_SONG)
            }
            if (action in setOf("playTrack", "playPlaylist", "playPause", "next", "previous")) {
                stopCast()
            }
            if (action in setOf("catalog", "tracks", "downloads", "playTrack", "playPlaylist",
                    "saveTrack", "savePlaylist", "importPlaylist")) {
                return@withContext musicLibrary.handle(request, active).also { reply ->
                    if (reply.optBoolean("ok") && action !in setOf("catalog", "tracks", "downloads")) {
                        RemoteActivity.record(RemoteActivity.ONEPLUS, reply.optString("message", action))
                    }
                }
            }
            val change = when (action) {
                "status" -> null
                // Pause also cancels a buffering start; play re-prepares after an error. Newer
                // controllers send the state they want, so a tap on a stale screen cannot undo
                // a change made on Samsung; older ones send nothing and toggle.
                "playPause" -> {
                    if (active.currentMediaItem == null) {
                        return@withContext JSONObject().put("ok", false).put("error", "No audio queued on server")
                    }
                    val playing = !Util.shouldShowPlayButton(active)
                    val want = wantsPlaying(if (request.has("play")) request.optBoolean("play") else null, playing)
                    if (want != playing) Util.handlePlayPauseButtonAction(active)
                    when {
                        want == playing -> if (want) "Play (already playing)" else "Pause (already paused)"
                        want -> "Play"
                        else -> "Pause"
                    }
                }
                "next" -> { active.seekToNextMediaItem(); "Next song" }
                "previous" -> { active.seekToPreviousMediaItem(); "Previous song" }
                "shuffle" -> {
                    active.shuffleModeEnabled =
                        if (request.has("on")) request.optBoolean("on") else !active.shuffleModeEnabled
                    if (active.shuffleModeEnabled) "Shuffle on" else "Shuffle off"
                }
                "volume" -> {
                    val percent = request.optInt("percent", -1)
                    if (percent !in 0..100) {
                        return@withContext JSONObject().put("ok", false).put("error", "Invalid volume")
                    }
                    val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent + 50) / 100, 0)
                    "Volume $percent%"
                }
                "setBreaks" -> {
                    val next = request.optJSONObject("breaks")?.let(MusicBreakSettings::fromJson)
                    if (next == null || !MusicBreakStore(this@RemoteServerService).save(next)) {
                        return@withContext JSONObject().put("ok", false).put("error", "Invalid break settings")
                    }
                    breaksActivityText(next)
                }
                else -> return@withContext JSONObject().put("ok", false).put("error", "Unknown command")
            }
            change?.let { RemoteActivity.record(RemoteActivity.ONEPLUS, it) }
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            JSONObject()
                .put("ok", true)
                .put("title", if (castSocket.get() != null) "OnePlus live audio"
                    else active.currentMediaItem?.mediaMetadata?.title?.toString() ?: "No track selected")
                .put("casting", castSocket.get() != null)
                .put("playing", !Util.shouldShowPlayButton(active))
                .put("hasTrack", active.currentMediaItem != null)
                .put("queueSize", active.mediaItemCount)
                .put("shuffle", active.shuffleModeEnabled)
                .put("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
                .put("breaks", MusicBreakStore(this@RemoteServerService).read().toJson())
                .put("breakSeconds", MusicBreakPolicy.secondsLeft(
                    MusicBreaks.resumeAt.value, SystemClock.elapsedRealtime()))
                // Optional fields; older controllers ignore them.
                .put("index", active.currentMediaItemIndex)
                .put("library", SavedPlaylistStore.shared(this@RemoteServerService).revision.value)
                .put("activity", RemoteActivity.toJson(RemoteActivity.entries.value, System.currentTimeMillis()))
        }

    override fun onDestroy() {
        running.value = false
        scope.cancel() // First, so the listener treats the socket close below as a normal stop.
        server?.close()
        stopCast()
        clients.forEach { it.close() }
        mediaFuture?.let(MediaController::releaseFuture)
        mediaFuture = null
        controller = null
        wifiLock?.release()
        wifiLock = null
        super.onDestroy()
    }

    companion object {
        private const val HANDSHAKE_MILLIS = 8_000L
        internal const val STALE_SONG = "Song already changed on Samsung"
        val running = MutableStateFlow(false)
        fun setEnabled(context: Context, enabled: Boolean) {
            val app = context.applicationContext
            val prefs = RemotePrefs(app)
            val previous = prefs.enabled
            prefs.enabled = enabled
            try {
                if (enabled) app.startForegroundService(Intent(app, RemoteServerService::class.java))
                else app.stopService(Intent(app, RemoteServerService::class.java))
            } catch (failure: RuntimeException) {
                prefs.enabled = previous
                throw failure
            }
        }
    }
}

/** A controller's explicit Play/Pause wins; an older controller sends none and toggles. */
internal fun wantsPlaying(requested: Boolean?, playing: Boolean): Boolean = requested ?: !playing

/** A skip aimed at a song Samsung already left is refused instead of skipping a second song. */
internal fun isStaleSkip(requestedIndex: Int?, currentIndex: Int): Boolean =
    requestedIndex != null && requestedIndex != currentIndex
