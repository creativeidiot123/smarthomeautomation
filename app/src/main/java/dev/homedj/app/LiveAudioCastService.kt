package dev.homedj.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal data class CastState(val active: Boolean = false, val connecting: Boolean = false, val error: String? = null)

/** A Stop action wins even if the blocking network job has not published its socket yet. */
internal class CastSocketLease {
    private var closed = false
    private var socket: Socket? = null

    @Synchronized fun attach(candidate: Socket) {
        if (closed) {
            candidate.close()
            throw IOException("Audio casting already stopped")
        }
        socket = candidate
    }

    @Synchronized fun close() {
        closed = true
        socket?.close()
        socket = null
    }
}

/** OnePlus owns consent and capture. No recording is persisted or sent outside the paired LAN. */
class LiveAudioCastService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private val castSocket = CastSocketLease()
    private val recorderLock = Any()
    @Volatile private var recorder: AudioRecord? = null
    private var projection: MediaProjection? = null
    @Volatile private var stopping = false
    // Sockets have no write timeout; a vanished Samsung would otherwise block a send for minutes.
    @Volatile private var sendStartedAt = 0L

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!stopping) {
                state.value = CastState(error = "Android ended audio capture. Start casting again.")
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || captureJob != null) {
            if (captureJob == null) stopSelf()
            return START_NOT_STICKY
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("oneplus_cast", "HomeDJ audio casting", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = Intent(this, LiveAudioCastService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 91, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, "oneplus_cast")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Sending OnePlus audio to Samsung")
            .setContentText("Capturing permitted app audio over encrypted Wi-Fi")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop casting", stopPending)
            .build()
        try {
            // startForegroundService() requires this before any stopSelf(); check permission after it.
            startForeground(91, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Audio capture permission missing")
            }
            val resultCode = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val grant = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else intent.getParcelableExtra(EXTRA_DATA)
            if (resultCode != Activity.RESULT_OK || grant == null) throw SecurityException("No capture grant")
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val ownedProjection = manager.getMediaProjection(resultCode, grant)
                ?: throw SecurityException("Capture grant unavailable")
            projection = ownedProjection
            ownedProjection.registerCallback(callback, Handler(Looper.getMainLooper()))
            val prefs = RemotePrefs(this)
            val host = prefs.savedHost
            val key = RemoteChannel.decodeKey(prefs.savedClientKey)
            if (!RemotePrefs.validPrivateIpv4(host) || key == null) throw IOException("Pair Samsung first")
            state.value = CastState(connecting = true)
            scope.launch {
                while (true) {
                    delay(1_000)
                    val sending = sendStartedAt
                    if (sending != 0L && SystemClock.elapsedRealtime() - sending > SEND_STALL_MILLIS) {
                        castSocket.close()
                    }
                }
            }
            captureJob = scope.launch {
                try {
                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(LiveAudioFormat.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
                    val config = AudioPlaybackCaptureConfiguration.Builder(ownedProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    val minimum = AudioRecord.getMinBufferSize(
                        LiveAudioFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minimum <= 0) throw IOException("Playback capture unavailable")
                    val active = AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setAudioPlaybackCaptureConfig(config)
                        // At most 100 ms waits here while Wi-Fi is slow: older audio only delays a
                        // live cast, and Samsung would drop it anyway.
                        .setBufferSizeInBytes(maxOf(minimum, LiveAudioFormat.CHUNK_BYTES * 5))
                        .build()
                    synchronized(recorderLock) {
                        if (stopping) {
                            active.release()
                            throw IOException("Audio casting already stopped")
                        }
                        recorder = active
                    }
                    if (active.state != AudioRecord.STATE_INITIALIZED) throw IOException("Capture device unavailable")
                    active.startRecording()
                    val frame = ByteArray(LiveAudioFormat.SEND_BYTES)
                    var started = false
                    RemoteChannel.streamAudio(host, key, onSocket = castSocket::attach) {
                        sendStartedAt = 0L // The previous frame has been written.
                        if (stopping) throw IOException("Audio casting stopped")
                        val read = active.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                        if (stopping || read <= 0) throw IOException("Audio capture stopped")
                        if (!started) {
                            synchronized(recorderLock) {
                                if (stopping) throw IOException("Audio casting stopped")
                                // Mute once per accepted cast; later volume changes belong to the user.
                                getSystemService(AudioManager::class.java)
                                    .setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                                started = true
                                state.value = CastState(active = true)
                            }
                        }
                        frame.copyOf(read - read % 4).takeIf { it.isNotEmpty() }
                            ?.also { sendStartedAt = SystemClock.elapsedRealtime() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!stopping) {
                        Log.w("HomeDJCast", "Audio cast ended: " + error.javaClass.simpleName)
                        state.value = CastState(error = "Casting stopped. Check Wi-Fi, permission and Samsung remote access.")
                    }
                } finally {
                    stopSelf()
                }
            }
        } catch (error: Exception) {
            Log.w("HomeDJCast", "Could not start capture: " + error.javaClass.simpleName)
            state.value = CastState(error = "Could not start audio capture. Check permission and pairing.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        runCatching { castSocket.close() }
            .onFailure { Log.w("HomeDJCast", "Cast socket shutdown failed", it) }
        synchronized(recorderLock) {
            recorder?.let { source ->
                runCatching { source.stop() }
                source.release()
            }
            recorder = null
        }
        projection?.unregisterCallback(callback)
        projection?.stop()
        projection = null
        captureJob?.cancel()
        scope.cancel()
        state.value = state.value.copy(active = false, connecting = false)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "dev.homedj.app.START_CAST"
        private const val ACTION_STOP = "dev.homedj.app.STOP_CAST"
        private const val EXTRA_CODE = "projection_code"
        private const val EXTRA_DATA = "projection_data"
        private const val SEND_STALL_MILLIS = 5_000L
        internal val state = MutableStateFlow(CastState())

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, LiveAudioCastService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data)
            )
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, LiveAudioCastService::class.java))
        }
    }
}
