package dev.homedj.app

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Service-owned envelopes for two decks. Only [active] owns the current queue. Main thread only. */
internal class PlaybackFades(
    first: Player,
    private val second: Player,
    private val scope: CoroutineScope,
    private val changed: (Player) -> Unit,
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
) {
    private val decks = listOf(first, second)
    var active: Player = first
        private set
    val spare: Player get() = decks.first { it !== active }
    private var outgoing: Player? = null
    private var envelope: Job? = null
    private var ending = false
    private var gain = mutableMapOf(first to 1f, second to 0f)
    var volume = 1f
        set(value) { field = value; applyGains() }
    var pausing = false
        private set
    val transitioning: Boolean get() = outgoing != null

    fun discardSpare() {
        if (outgoing != null) cancelEnvelope()
        outgoing = null
        retire(spare)
    }

    /** The caller configures/prepares the spare before making it authoritative. Latest intent wins. */
    fun switchToPrepared(millis: Long = MANUAL_SONG_MILLIS) {
        cancelEnvelope()
        ending = false
        outgoing = null
        val old = active
        val incoming = spare
        val playing = old.playWhenReady && !pausing
        pausing = false
        if (old.isPlaying) outgoing = old else retire(old)
        active = incoming
        gain[incoming] = if (playing) 0f else 1f
        applyGains()
        incoming.playWhenReady = playing
        changed(incoming)
        if (playing) fadeIn(millis) else retire(old)
    }

    fun play(millis: Long = CONTROL_MILLIS) {
        if (active.playWhenReady && !pausing && !ending) return
        cancelEnvelope()
        ending = false
        pausing = false
        if (!active.playWhenReady) gain[active] = 0f
        applyGains()
        active.play()
        fadeIn(millis)
    }

    fun pause() {
        if (pausing) return
        cancelEnvelope()
        pausing = true
        if (!active.isPlaying && outgoing?.isPlaying != true) {
            silence()
            return
        }
        val initial = decks.map { gain.getValue(it) }
        envelope = scope.launch {
            ramp(CONTROL_MILLIS) { progress ->
                decks.forEachIndexed { index, deck -> gain[deck] = initial[index] * (1f - smooth(progress)) }
                applyGains()
            }
            silence()
        }
    }

    /** Fade the last seconds, leaving Media3 to pause at the natural end for a full break. */
    fun fadeAtEnd(millis: Long) {
        if (ending || transitioning || pausing) return
        cancelEnvelope()
        ending = true
        val initial = gain.getValue(active)
        envelope = scope.launch {
            ramp(millis) { progress ->
                gain[active] = initial * (1f - smooth(progress))
                applyGains()
            }
        }
    }

    fun cancelEndFade() { if (ending) play() }

    /** Focus/noisy/cast/role/teardown overrides every envelope without a delayed restart. */
    fun silence() {
        cancelEnvelope()
        ending = false
        outgoing = null
        decks.forEach { it.pause(); gain[it] = 0f }
        spare.stop()
        spare.clearMediaItems()
        pausing = false
        applyGains()
    }

    fun stop() {
        silence()
        active.stop()
    }

    fun release() {
        cancelEnvelope()
        decks.forEach(Player::release)
        outgoing = null
    }

    private fun fadeIn(millis: Long) {
        val incoming = active
        val old = outgoing
        val start = gain.getValue(incoming)
        val oldStart = old?.let { gain.getValue(it) } ?: 0f
        envelope = scope.launch {
            // Keep the old song audible while the new source buffers, with a bounded tail.
            var waited = 0L
            while (!incoming.isPlaying && incoming.playWhenReady && incoming.playerError == null && waited < LOAD_MILLIS) {
                waitMillis(STEP_MILLIS)
                waited += STEP_MILLIS
            }
            if (!incoming.isPlaying) {
                old?.let(::retire)
                outgoing = null
                gain[incoming] = 1f
                applyGains()
                return@launch
            }
            ramp(millis) { progress ->
                if (old != null) {
                    // Light easing softens both ends without holding the incoming song near silence.
                    val angle = (0.75f * progress + 0.25f * smooth(progress)) * PI / 2
                    gain[incoming] = start + (1f - start) * sin(angle).toFloat()
                    gain[old] = oldStart * cos(angle).toFloat()
                } else gain[incoming] = start + (1f - start) * smooth(progress)
                applyGains()
            }
            old?.let(::retire)
            outgoing = null
            gain[incoming] = 1f
            applyGains()
        }
    }

    private suspend fun ramp(millis: Long, frame: (Float) -> Unit) {
        val steps = (millis / STEP_MILLIS).toInt().coerceAtLeast(1)
        for (step in 1..steps) {
            waitMillis(millis / steps)
            frame(step.toFloat() / steps)
        }
    }

    private fun retire(deck: Player) {
        deck.pause()
        deck.stop()
        deck.clearMediaItems()
        gain[deck] = 0f
    }

    private fun applyGains() { decks.forEach { it.volume = volume * gain.getValue(it) } }
    private fun cancelEnvelope() { envelope?.cancel(); envelope = null }

    companion object {
        const val SONG_MILLIS = 4_000L
        private const val MANUAL_SONG_MILLIS = 3_000L
        const val CONTROL_MILLIS = 5_000L
        private const val LOAD_MILLIS = 15_000L
        private const val STEP_MILLIS = 50L
        private fun smooth(progress: Float): Float = progress * progress * (3f - 2f * progress)
    }
}
