package dev.homedj.app

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Samsung-only loop in [RemoteServerService]'s scope (its wake/Wi-Fi locks keep it running),
 * beside [LightVariationRunner]. While dance is on and the Samsung's music plays, it plans each
 * beat from [MusicPulse] and sends every cue early by the bulbs' measured answer time, so the
 * light changes on the beat; a cue that is already late is dropped, never queued up. Nothing is
 * committed: lights that stop dancing (setting off, music gone, light turned off) go back to their record.
 * [songArt] is the current song's artwork URL, read from the Samsung's own Media3 session.
 */
internal class LightDanceRunner(private val context: Context, private val songArt: suspend () -> String?) {
    private val danced = HashSet<Long>()
    private val failures = HashMap<Long, Int>()
    private val resting = HashMap<Long, Long>()
    /** Cues due to be sent, by send time. */
    private val pending = ArrayList<DanceCue>()
    private var beatIndex = 0L
    /** When the last planned beat should show (elapsed ms); the next one is at least half a beat later. */
    private var plannedAt = 0L
    private var periodMillis = LightDances.FREE_PERIOD_MILLIS
    /** Smoothed time for a bulb to acknowledge a cue; half of it is sent ahead of the beat. */
    private var answerMillis = 120.0
    private var quietSince = 0L
    @Volatile private var hues: List<Int> = emptyList()

    suspend fun run() = coroutineScope {
        val state = Lights.state(context)
        launch { followArtwork(state) }
        try {
            dance(state)
        } finally {
            mutableStatus.value = ""
        }
    }

    private suspend fun dance(state: StateFlow<LightsSnapshot>) {
        while (true) {
            if (danced.isEmpty()) mutableStatus.value = ""
            val snapshot = state.first { it.dance.enabled || danced.isNotEmpty() }
            val now = SystemClock.elapsedRealtime()
            val music = MusicPulse.now(now)?.takeIf { snapshot.dance.enabled && !it.reading.silent }
            if (music != null) quietSince = 0L else if (quietSince == 0L) quietSince = now
            // A short gap between songs or a quiet bar keeps the lights dancing; a real stop lets them go.
            val active = snapshot.dance.enabled && (music != null || now - quietSince < QUIET_MILLIS)
            val dancers = if (active) LightDances.dancers(snapshot.bulbs).filter { (resting[it.id] ?: 0L) <= now } else emptyList()
            val ids = dancers.map(Bulb::id).toSet()
            (danced - ids).forEach { restore(it) }
            pending.removeAll { it.bulb !in ids }
            mutableStatus.value = when {
                !snapshot.dance.enabled -> ""
                !active -> "Waiting for music on the Samsung"
                dancers.isEmpty() -> "Turn a light on to dance"
                music?.reading?.locked == true -> "Dancing · ${(60_000.0 / music.periodMillis).roundToInt()} BPM"
                else -> "Finding the beat…"
            }
            if (dancers.isEmpty()) {
                pending.clear()
                withTimeoutOrNull(IDLE_MILLIS) { state.first { it.dance != snapshot.dance || it.bulbs != snapshot.bulbs } }
                continue
            }
            if (pending.isEmpty()) plan(snapshot.dance, music, dancers, now)
            val next = pending.minByOrNull { it.at } ?: continue
            val wait = next.at - SystemClock.elapsedRealtime()
            if (wait > 0) {
                // Short naps, so a changed setting, style or light is picked up within a beat.
                delay(minOf(wait, NAP_MILLIS))
                continue
            }
            pending.remove(next)
            if (-wait <= maxOf(STALE_MILLIS, periodMillis / 4L)) send(next)
        }
    }

    /** The next beat the lights can still reach (or a free breath without a steady beat), as send-time cues. */
    private fun plan(dance: LightDance, music: MusicNow?, dancers: List<Bulb>, now: Long) {
        val lead = (answerMillis / 2).toLong().coerceIn(20L, 300L)
        val reachable = now + lead - dance.offsetMillis
        val beat = music?.takeIf { it.reading.locked }?.let { pulse ->
            periodMillis = pulse.periodMillis.toInt().coerceAtLeast(1)
            pulse.beatsFrom(reachable, 3).map(pulse::wallMillis).firstOrNull { it > plannedAt + periodMillis / 2 }
                ?.let { DanceBeat(++beatIndex, it + dance.offsetMillis, periodMillis, pulse.reading.energy, pulse.reading.surge) }
        } ?: run {
            periodMillis = LightDances.FREE_PERIOD_MILLIS
            val at = maxOf(reachable, plannedAt - dance.offsetMillis + periodMillis)
            DanceBeat(++beatIndex, at + dance.offsetMillis, periodMillis, music?.reading?.energy ?: 0.3f, false)
        }
        plannedAt = beat.at - dance.offsetMillis
        val style = if (music?.reading?.locked == true) dance.style else LightDanceStyle.CHILL
        pending += LightDances.beat(style, beat, dancers, hues).map { it.copy(at = it.at - lead) }
    }

    private suspend fun send(cue: DanceCue) {
        val started = SystemClock.elapsedRealtime()
        try {
            if (Lights.dance(context, cue.bulb) { LightDances.fit(it, cue.light) }) {
                danced += cue.bulb
                val took = (SystemClock.elapsedRealtime() - started).coerceAtMost(600L)
                answerMillis += 0.2 * (took - answerMillis)
            }
            failures -= cue.bulb
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Lights.logFailure("Light dance", failure)
            // An unreachable light sits out a while instead of holding the Matter controller every beat.
            val count = (failures[cue.bulb] ?: 0) + 1
            failures[cue.bulb] = count
            resting[cue.bulb] = SystemClock.elapsedRealtime() + (2_000L shl (count - 1).coerceIn(0, 4))
            pending.removeAll { it.bulb == cue.bulb }
        }
    }

    /** Back to the recorded brightness and colour once; a light that was turned off stays off. */
    private suspend fun restore(id: Long) {
        danced -= id
        try {
            Lights.dance(context, id, LightVariations::restore)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Lights.logFailure("Light dance restore", failure)
        }
    }

    /** Re-reads the song's artwork every few seconds while dancing; a new song brings its own palette. */
    private suspend fun followArtwork(state: StateFlow<LightsSnapshot>) {
        var art: String? = null
        while (true) {
            state.first { it.dance.enabled }
            try {
                val url = songArt()
                if (url != art) {
                    art = url
                    hues = url?.let { loadArtworkHues(context, it) }.orEmpty()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Lights.logFailure("Light dance artwork", failure)
            }
            delay(ART_MILLIS)
        }
    }

    companion object {
        private const val QUIET_MILLIS = 4_000L
        private const val IDLE_MILLIS = 500L
        private const val NAP_MILLIS = 100L
        private const val STALE_MILLIS = 80L
        private const val ART_MILLIS = 2_000L
        private val mutableStatus = MutableStateFlow("")
        /** Samsung's live dance status for its own screen and the OnePlus reply; empty when not dancing. */
        val status: StateFlow<String> = mutableStatus.asStateFlow()
    }
}
