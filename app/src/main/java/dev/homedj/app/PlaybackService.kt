package dev.homedj.app

import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
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
    private var player: PlaybackPlayer? = null
    private var librarySession: MediaLibrarySession? = null
    private var breaks: MusicBreaks? = null

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun onCreate() {
        super.onCreate()
        val resolver = HomeDjMediaResolver(this)
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this), resolver)
        fun deck() = ExoPlayer.Builder(this, normalizedRenderers(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false, // PlaybackPlayer holds one audio-focus request for both decks.
            )
            .build()
        val localPlayer = PlaybackPlayer(this, deck(), deck(), roleScope)
        player = localPlayer
        // Every new queue starts shuffled; the shuffle button still turns it off for that queue.
        localPlayer.shuffleModeEnabled = true
        localPlayer.addListener(PlaybackRecovery(localPlayer, resolver, roleScope))
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeDJ:musicBreak")
            .apply { setReferenceCounted(false) }
        val musicBreaks = MusicBreaks(
            localPlayer, roleScope,
            acquireWakeLock = { timeout -> wakeLock.acquire(timeout) },
            releaseWakeLock = { if (wakeLock.isHeld) wakeLock.release() },
            onHoldReleased = { librarySession?.let { onUpdateNotification(it, false) } },
            canResume = {
                !LiveAudioSink.active && MusicScheduler.queueMayPlayNow(this@PlaybackService, localPlayer)
            },
            pauseAtEnd = { localPlayer.pauseAtEndOfMediaItems },
            setPauseAtEnd = { localPlayer.pauseAtEndOfMediaItems = it },
            resumeWithFade = localPlayer::playWithFade,
        )
        breaks = musicBreaks
        localPlayer.onSafetyPause = musicBreaks::onUserPause
        localPlayer.addListener(musicBreaks)
        val scheduler = MusicScheduler(this, localPlayer, roleScope, musicBreaks)
        localPlayer.addListener(scheduler)
        librarySession = MediaLibrarySession.Builder(
            this,
            UserCommandPlayer(localPlayer, musicBreaks),
            object : MediaLibrarySession.Callback {
                override fun onConnect(
                    session: MediaSession, controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult =
                    scheduler.connectionResult(session, controller) ?: super.onConnect(session, controller)

                override fun onCustomCommand(
                    session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle,
                ): ListenableFuture<SessionResult> = scheduler.onCustomCommand(customCommand, args)

                override fun onSetMediaItems(
                    mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    localPlayer.shuffleModeEnabled = true
                    return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
                }
            },
        ).build()
        roleScope.launch {
            ModeStore(this@PlaybackService).mode
                .catch { error ->
                    Log.e("HomeDJ", "Role preference unavailable; stopping playback", error)
                    emit(null)
                }
                .collect { mode ->
                    if (mode != HomeDjMode.SERVER) {
                        musicBreaks.cancel()
                        localPlayer.stop()
                        localPlayer.clearMediaItems()
                        ScheduleAlarms.cancel(this@PlaybackService)
                    } else {
                        scheduler.arm()
                    }
                }
        }
        roleScope.launch {
            MusicBreakStore(this@PlaybackService).settings.collect(musicBreaks::update)
        }
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        if (controllerInfo.uid == Process.myUid() || controllerInfo.isTrusted) librarySession
        else null

    /** A music break pauses on purpose; staying foreground lets its resume start playback again. */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired || breaks?.onBreak == true)
    }

    override fun onDestroy() {
        roleScope.cancel()
        breaks?.release()
        breaks = null
        librarySession?.release()
        librarySession = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
