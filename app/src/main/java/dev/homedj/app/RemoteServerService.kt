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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.SocketException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    override fun onBind(intent: Intent?): IBinder? = null

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
                val client = withContext(Dispatchers.IO) { listener.accept() }
                try {
                    client.use { socket ->
                        socket.soTimeout = 5_000
                        RemoteChannel.serve(
                            DataInputStream(socket.getInputStream()),
                            DataOutputStream(socket.getOutputStream()),
                            key,
                        ) { request ->
                            handle(request)
                        }
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    Log.w("HomeDJRemote", "Rejected LAN request: " + failure.javaClass.simpleName)
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

    private suspend fun handle(request: JSONObject): JSONObject =
        withContext(Dispatchers.Main.immediate) {
            if (!RemotePrefs(this@RemoteServerService).enabled ||
                ModeStore(this@RemoteServerService).mode.first() != HomeDjMode.SERVER
            ) {
                return@withContext JSONObject().put("ok", false).put("error", "Server role inactive")
            }
            val active = controller ?: return@withContext JSONObject()
                .put("ok", false).put("error", "Playback service not ready")
            val action = request.optString("command")
            if (action in setOf("catalog", "tracks", "downloads", "playTrack", "playPlaylist",
                    "saveTrack", "savePlaylist")) {
                return@withContext musicLibrary.handle(request, active)
            }
            when (action) {
                "status" -> Unit
                "playPause" -> if (active.isPlaying) active.pause()
                    else if (active.currentMediaItem != null) active.play()
                    else return@withContext JSONObject().put("ok", false).put("error", "No audio queued on server")
                "next" -> active.seekToNextMediaItem()
                "previous" -> active.seekToPreviousMediaItem()
                "shuffle" -> active.shuffleModeEnabled = !active.shuffleModeEnabled
                "volume" -> {
                    val percent = request.optInt("percent", -1)
                    if (percent !in 0..100) {
                        return@withContext JSONObject().put("ok", false).put("error", "Invalid volume")
                    }
                    val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent + 50) / 100, 0)
                }
                else -> return@withContext JSONObject().put("ok", false).put("error", "Unknown command")
            }
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            JSONObject()
                .put("ok", true)
                .put("title", active.currentMediaItem?.mediaMetadata?.title?.toString() ?: "No track selected")
                .put("playing", active.isPlaying)
                .put("hasTrack", active.currentMediaItem != null)
                .put("queueSize", active.mediaItemCount)
                .put("shuffle", active.shuffleModeEnabled)
                .put("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
        }

    override fun onDestroy() {
        running.value = false
        server?.close()
        scope.cancel()
        mediaFuture?.let(MediaController::releaseFuture)
        mediaFuture = null
        controller = null
        super.onDestroy()
    }

    companion object {
        val running = MutableStateFlow(false)
        fun setEnabled(context: Context, enabled: Boolean) {
            val app = context.applicationContext
            RemotePrefs(app).enabled = enabled
            if (enabled) app.startForegroundService(Intent(app, RemoteServerService::class.java))
            else app.stopService(Intent(app, RemoteServerService::class.java))
        }
    }
}

