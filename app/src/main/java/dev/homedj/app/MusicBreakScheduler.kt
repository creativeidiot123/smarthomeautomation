package dev.homedj.app

import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
 * main thread inside [PlaybackService]: it only fades, pauses and resumes the sole player and never
 * touches the queue. Music time counts only while audio is actually playing. Every external
 * play/pause arrives through [UserCommandPlayer]: Play ends a break, Pause cancels its resume.
 * [MusicScheduler] ends a break when a slot ends ([cancel]) or starts ([restart]); [canResume]
 * re-checks at resume time that the queue may still play (its slot is active, nothing is casting).
 */
internal class MusicBreaks(
    private val player: Player,
    private val scope: CoroutineScope,
    private val wakeLock: PowerManager.WakeLock,
    private val onHoldReleased: () -> Unit,
    private val canResume: suspend () -> Boolean,
    private val random: Random = Random.Default,
) : Player.Listener {
    private var settings = MusicBreakSettings()
    private var musicLeft = 0L // Zero rolls a new music session on the next play.
    private var clockStarted = 0L
    private var clock: Job? = null
    private var breakJob: Job? = null // Fade-out, silence, then resume.
    private var fadeInJob: Job? = null
    private var fadeInPending = false

    /** The player is paused by a break; the service stays foreground so it may resume. */
    var onBreak = false
        private set

    fun update(next: MusicBreakSettings) {
        val wasEnabled = settings.enabled
        settings = next
        if (!next.enabled && wasEnabled) cancel()
        else if (next.enabled && !wasEnabled && player.isPlaying) startClock()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) {
            stopClock()
            return
        }
        startClock()
        if (fadeInPending) {
            fadeInPending = false
            val millis = MusicBreakPolicy.fadeMillis(settings, random)
            fadeInJob = scope.launch {
                ramp(fadeIn = true, millis)
                fadeInJob = null
            }
        }
    }

    fun onUserPlay() {
        if (stopBreak()) musicLeft = 0
        stopFadeIn()
    }

    fun onUserPause() {
        val wasOnBreak = onBreak
        if (stopBreak()) musicLeft = 0
        stopFadeIn()
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
        stopFadeIn()
    }

    /** Service teardown; the player is released right after, so leave it alone. */
    fun release() {
        clock?.cancel()
        breakJob?.cancel()
        fadeInJob?.cancel()
        onBreak = false
        if (wakeLock.isHeld) wakeLock.release()
        mutableResumeAt.value = null
    }

    private fun startClock() {
        if (!settings.enabled || breakJob != null || clock != null) return
        if (musicLeft <= 0) musicLeft = MusicBreakPolicy.musicMillis(settings, random)
        clockStarted = SystemClock.elapsedRealtime()
        val left = musicLeft
        clock = scope.launch {
            delay(left)
            clock = null
            musicLeft = 0
            startBreak()
        }
    }

    private fun stopClock() {
        val running = clock ?: return
        running.cancel()
        clock = null
        musicLeft = (musicLeft - (SystemClock.elapsedRealtime() - clockStarted)).coerceAtLeast(0)
    }

    private fun startBreak() {
        val fadeOut = MusicBreakPolicy.fadeMillis(settings, random)
        val silence = MusicBreakPolicy.silenceMillis(settings, random)
        breakJob = scope.launch {
            ramp(fadeIn = false, fadeOut)
            onBreak = true // Before pause, so Media3's notification refresh keeps the service foreground.
            wakeLock.acquire(silence + WAKE_MARGIN_MILLIS) // Keeps the timer on time with the screen off.
            mutableResumeAt.value = SystemClock.elapsedRealtime() + silence
            player.pause()
            player.volume = 1f
            delay(silence)
            // Still inside the break job, so a play/pause meanwhile cancels this check.
            val resume = resumeAllowed()
            breakJob = null
            onBreak = false
            if (wakeLock.isHeld) wakeLock.release()
            mutableResumeAt.value = null
            val state = player.playbackState
            if (!resume || player.currentMediaItem == null ||
                (state != Player.STATE_READY && state != Player.STATE_BUFFERING)
            ) {
                onHoldReleased()
                return@launch
            }
            player.volume = 0f
            fadeInPending = true
            player.play()
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

    /** Returns whether a fade-out or break was running; restores full volume. */
    private fun stopBreak(): Boolean {
        val running = breakJob ?: return false
        running.cancel()
        breakJob = null
        onBreak = false
        if (wakeLock.isHeld) wakeLock.release()
        mutableResumeAt.value = null
        player.volume = 1f
        return true
    }

    private fun stopFadeIn() {
        fadeInJob?.cancel()
        fadeInJob = null
        fadeInPending = false
        player.volume = 1f
    }

    private suspend fun ramp(fadeIn: Boolean, millis: Long) {
        val steps = (millis / FADE_STEP_MILLIS).toInt().coerceAtLeast(1)
        for (step in 1..steps) {
            delay(millis / steps)
            val progress = step.toFloat() / steps
            player.volume = MusicBreakPolicy.gain(if (fadeIn) progress else 1f - progress)
        }
    }

    companion object {
        private const val FADE_STEP_MILLIS = 100L
        private const val WAKE_MARGIN_MILLIS = 60_000L
        private val mutableResumeAt = MutableStateFlow<Long?>(null)

        /** `elapsedRealtime` when the running break ends; a projection of the service's scheduler. */
        val resumeAt = mutableResumeAt.asStateFlow()
    }
}

/**
 * The session's view of the player, so controller, notification and media-button play/pause reach
 * [MusicBreaks]. Pause goes first so full volume is restored only once the audio has stopped.
 * A skip after a failed track re-prepares, since a seek alone leaves an errored player idle.
 */
internal class UserCommandPlayer(
    private val player: ExoPlayer, private val breaks: MusicBreaks,
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
