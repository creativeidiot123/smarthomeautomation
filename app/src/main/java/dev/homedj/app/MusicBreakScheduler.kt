package dev.homedj.app

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Samsung's device-local music-break settings; the only persistent owner. */
internal class MusicBreakStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("homedj_music_breaks", Context.MODE_PRIVATE)

    fun read(): MusicBreakSettings =
        prefs.getString(KEY, null)
            ?.let { stored -> runCatching { MusicBreakSettings.fromJson(JSONObject(stored)) }.getOrNull() }
            ?: MusicBreakSettings()

    fun save(settings: MusicBreakSettings): Boolean {
        if (!settings.isValid()) return false
        prefs.edit().putString(KEY, settings.toJson().toString()).apply()
        return true
    }

    val settings: Flow<MusicBreakSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY) trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()

    private companion object {
        const val KEY = "settings"
    }
}

/**
 * Splits the service's playback into random music sessions and silences. Runs on the player's
 * main thread inside [PlaybackService]: it waits for song ends, pauses and resumes the session and never
 * touches the queue. Music time counts only while audio is actually playing. Every external
 * play/pause arrives through [UserCommandPlayer]: Play ends a break, Pause cancels its resume. A skip
 * or picked song keeps a break that is due after the current song; it then follows the new song.
 * [MusicScheduler] ends a break when a slot ends ([cancel]) or starts ([restart]); [canResume]
 * re-checks at resume time that the queue may still play (its slot is active, nothing is casting).
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class MusicBreaks(
    private val player: Player,
    private val scope: CoroutineScope,
    private val acquireWakeLock: (Long) -> Unit,
    private val releaseWakeLock: () -> Unit,
    private val onHoldReleased: () -> Unit,
    private val canResume: suspend () -> Boolean,
    private val pauseAtEnd: () -> Boolean,
    private val setPauseAtEnd: (Boolean) -> Unit,
    private val resumeWithFade: (Long) -> Unit,
    private val random: Random = Random.Default,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
) : Player.Listener {
    private var settings = MusicBreakSettings()
    private var musicLeft = 0L // Zero rolls a new music session on the next play.
    private var clockStarted = 0L
    private var clock: Job? = null
    private var breakJob: Job? = null // Silence, then resume.

    /** The player is paused by a break; the service stays foreground so it may resume. */
    var onBreak = false
        private set

    fun update(next: MusicBreakSettings) {
        val wasEnabled = settings.enabled
        settings = next
        if (!next.enabled && wasEnabled) cancel()
        else if (next.enabled && !wasEnabled && player.isPlaying) startClock()
        publish()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) startClock() else stopClock()
        publish()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
            pauseAtEnd() && settings.enabled && breakJob == null
        ) startBreak()
        publish()
    }

    fun onUserPlay() {
        // A Play while music already plays (after a picked song) keeps a break due after this song.
        if ((breakJob != null || !player.playWhenReady) && stopBreak()) musicLeft = 0
        if (player.isPlaying) startClock()
        publish()
    }

    fun onUserPause() {
        val wasOnBreak = onBreak
        if (stopBreak()) musicLeft = 0
        publish()
        if (wasOnBreak) onHoldReleased()
    }

    /** Breaks off, role change or slot end: a paused break stays paused, nothing auto-resumes. */
    fun cancel() {
        val wasOnBreak = onBreak
        restart()
        if (wasOnBreak) onHoldReleased()
    }

    /**
     * A scheduled queue is about to play: drop any break and roll a fresh music session. The
     * foreground hold is not released here because the play that follows refreshes it.
     */
    fun restart() {
        stopClock()
        musicLeft = 0
        stopBreak()
        publish()
    }

    /** Service teardown; the player is released right after, so leave it alone. */
    fun release() {
        clock?.cancel()
        breakJob?.cancel()
        onBreak = false
        releaseWakeLock()
        mutableResumeAt.value = null
        mutableNextBreak.value = null
    }

    private fun startClock() {
        if (!settings.enabled || breakJob != null || clock != null || pauseAtEnd()) return
        if (musicLeft <= 0) musicLeft = MusicBreakPolicy.musicMillis(settings, random)
        clockStarted = now()
        val left = musicLeft
        clock = scope.launch {
            waitMillis(left)
            clock = null
            musicLeft = 0
            setPauseAtEnd(true)
            publish()
        }
    }

    private fun stopClock() {
        val running = clock ?: return
        running.cancel()
        clock = null
        musicLeft = (musicLeft - (now() - clockStarted)).coerceAtLeast(0)
    }

    private fun startBreak() {
        val silence = MusicBreakPolicy.silenceMillis(settings, random)
        breakJob = scope.launch {
            onBreak = true // Before pause, so Media3's notification refresh keeps the service foreground.
            acquireWakeLock(silence + WAKE_MARGIN_MILLIS) // Keeps the timer on time with the screen off.
            mutableResumeAt.value = now() + silence
            mutableNextBreak.value = null
            player.pause()
            setPauseAtEnd(false)
            waitMillis(silence)
            // Still inside the break job, so a play/pause meanwhile cancels this check.
            val resume = resumeAllowed()
            breakJob = null
            onBreak = false
            releaseWakeLock()
            mutableResumeAt.value = null
            publish()
            val state = player.playbackState
            if (!resume || player.currentMediaItem == null ||
                (state != Player.STATE_READY && state != Player.STATE_BUFFERING)
            ) {
                onHoldReleased()
                return@launch
            }
            resumeWithFade(MusicBreakPolicy.fadeMillis(settings, random))
        }
    }

    private suspend fun resumeAllowed(): Boolean =
        try {
            canResume()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJ", "Music break resume check failed: " + failure.javaClass.simpleName)
            false
        }

    /**
     * Music left before the next break, for both dashboards. Checked in this order because a
     * crossfade also arms the song-end pause while a music session is still counting.
     */
    private fun publish() {
        mutableNextBreak.value = when {
            !settings.enabled || breakJob != null -> null
            clock != null -> NextBreak(musicLeft, clockStarted, counting = true)
            musicLeft > 0 -> NextBreak(musicLeft, now(), counting = false)
            pauseAtEnd() -> NextBreak(0, now(), counting = false)
            else -> null
        }
    }

    /** Returns whether a song-end pause or break was pending. */
    private fun stopBreak(): Boolean {
        val waiting = pauseAtEnd()
        setPauseAtEnd(false)
        val running = breakJob ?: return waiting
        running.cancel()
        breakJob = null
        onBreak = false
        releaseWakeLock()
        mutableResumeAt.value = null
        return true
    }

    companion object {
        private const val WAKE_MARGIN_MILLIS = 60_000L
        private val mutableResumeAt = MutableStateFlow<Long?>(null)

        /** `elapsedRealtime` when the running break ends; a projection of the service's scheduler. */
        val resumeAt = mutableResumeAt.asStateFlow()
        private val mutableNextBreak = MutableStateFlow<NextBreak?>(null)

        /** Music left before the next break; null while off, on a break or before the first song. */
        val nextBreak = mutableNextBreak.asStateFlow()
    }
}

/**
 * The session's view of the player, so controller, notification and media-button play/pause reach
 * [MusicBreaks]. PlaybackPlayer owns all audio envelopes, including a pause's fading tail.
 * A skip after a failed track re-prepares, since a seek alone leaves an errored player idle.
 */
internal class UserCommandPlayer(
    private val player: PlaybackPlayer, private val breaks: MusicBreaks,
) : ForwardingPlayer(player) {
    /** A tapped song in a shuffled queue plays first; Media3 would start it mid order and skip the songs before it. */
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
        if (player.shuffleModeEnabled && startIndex in 0 until player.mediaItemCount) {
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(
                shuffleOrderStartingAt(player.mediaItemCount, startIndex, Random.Default), Random.nextLong()))
        }
    }

    override fun seekToNext() = super.seekToNext().also { prepareAfterError() }
    override fun seekToPrevious() = super.seekToPrevious().also { prepareAfterError() }
    override fun seekToNextMediaItem() = super.seekToNextMediaItem().also { prepareAfterError() }
    override fun seekToPreviousMediaItem() = super.seekToPreviousMediaItem().also { prepareAfterError() }
    override fun seekToDefaultPosition(mediaItemIndex: Int) =
        super.seekToDefaultPosition(mediaItemIndex).also { prepareAfterError() }

    private fun prepareAfterError() {
        if (player.playerError != null) player.prepare()
    }

    override fun play() {
        breaks.onUserPlay()
        super.play()
    }

    override fun pause() {
        super.pause()
        breaks.onUserPause()
    }

    override fun stop() {
        super.stop()
        breaks.onUserPause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) {
            breaks.onUserPlay()
            super.setPlayWhenReady(true)
        } else {
            super.setPlayWhenReady(false)
            breaks.onUserPause()
        }
    }
}
