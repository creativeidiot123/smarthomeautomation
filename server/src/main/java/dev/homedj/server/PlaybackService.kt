package dev.homedj.server

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession

/**
 * Sole owner of audio, queue and playback state. A controller UI never creates its own player.
 * Media3 manages its media-playback foreground notification while audio is ongoing.
 */
class PlaybackService : MediaLibraryService() {
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        librarySession

    override fun onDestroy() {
        librarySession?.release()
        librarySession = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
