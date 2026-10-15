package dev.homedj.app

import android.os.Process
import android.content.Intent
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.first
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Sole owner of audio, queue and playback state. A controller UI never creates its own player.
 * Media3 manages its media-playback foreground notification while audio is ongoing.
 */
class PlaybackService : MediaLibraryService() {
    private val roleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var librarySession: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val localPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = localPlayer
        librarySession = MediaLibrarySession.Builder(
            this,
            localPlayer,
            object : MediaLibrarySession.Callback {},
        ).build()
        roleScope.launch {
            ModeStore(this@PlaybackService).mode
                .catch { error ->
                    Log.e("HomeDJ", "Role preference unavailable; stopping playback", error)
                    emit(null)
                }
                .collect { mode ->
                    if (mode != HomeDjMode.SERVER) {
                        localPlayer.stop()
                        localPlayer.clearMediaItems()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (intent?.action == StreamResolution.ACTION_RESULT) {
            val title = StreamResolution.consume(this, intent)
            if (title != null) {
                val resolved = StreamResolution.playableUrl(intent.getBundleExtra("result"))
                if (resolved == null) {
                    Log.w("HomeDJStream", "Termux failed to resolve a playable audio URL")
                } else {
                    roleScope.launch {
                        if (ModeStore(this@PlaybackService).mode.first() != HomeDjMode.SERVER) return@launch
                        player?.apply {
                            setMediaItem(
                                MediaItem.Builder().setUri(resolved)
                                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                                    .build()
                            )
                            prepare()
                            play()
                        }
                    }
                }
            } else {
                Log.w("HomeDJStream", "Ignored stale or unrecognized stream resolution callback")
            }
        }
        return result
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        if (controllerInfo.uid == Process.myUid() || controllerInfo.isTrusted) librarySession
        else null

    override fun onDestroy() {
        roleScope.cancel()
        librarySession?.release()
        librarySession = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
