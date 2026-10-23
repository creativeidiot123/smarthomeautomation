package dev.homedj.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatTrackerTest {
    private val rate = 44_100

    /**
     * Kick on every beat, quieter hi-hat on the off-beat, low noise underneath; starts at [startUs].
     * [tone] adds a held voice-range note (220 Hz with harmonics and vibrato) at that amplitude.
     */
    private fun play(tracker: BeatTracker, bpm: Double, seconds: Double, startUs: Long = 0L, gain: Float = 1f,
        random: Random = Random(7), kickGain: Double = 0.8, hatGain: Double = 0.15, tone: Double = 0.0) {
        val beatSamples = rate * 60.0 / bpm
        val total = (seconds * rate).toInt()
        var index = 0
        var lastDraw = 0.0
        while (index < total) {
            tracker.begin(startUs + index * 1_000_000L / rate)
            for (i in index until minOf(index + 1024, total)) {
                val inBeat = i % beatSamples
                val t = inBeat / rate
                val kick = if (t < 0.15) sin(2 * PI * 60 * t) * exp(-t * 25) else 0.0
                val offT = (i + beatSamples / 2) % beatSamples / rate
                // High-passed noise (each draw minus the one before), as a real hi-hat has almost nothing in the bass.
                val draw = random.nextDouble() * 2 - 1
                val hat = if (offT < 0.03) (draw - lastDraw) * hatGain * exp(-offT * 120) else 0.0
                lastDraw = draw
                val noise = (random.nextDouble() * 2 - 1) * 0.01
                val s = i.toDouble() / rate
                val phase = 2 * PI * 220 * s + 0.4 * sin(2 * PI * 5 * s)
                val held = tone * (sin(phase) + 0.6 * sin(2 * phase) + 0.4 * sin(3 * phase) + 0.25 * sin(4 * phase))
                tracker.add(((kick * kickGain + hat + noise + held) * gain).toFloat())
            }
            index += 1024
        }
    }

    /** Distance from the grid to the nearest kick, in µs. */
    private fun phaseError(reading: PulseReading, bpm: Double, startUs: Long = 0L): Long {
        val beatUs = 60_000_000.0 / bpm
        val offset = Math.floorMod((reading.beatUs - startUs), beatUs.toLong())
        return minOf(offset, beatUs.toLong() - offset)
    }

    @Test fun locksTempoAndPhaseOfCommonDanceTempos() {
        listOf(95.0, 124.0, 150.0).forEach { bpm ->
            val tracker = BeatTracker(rate)
            play(tracker, bpm, 12.0)
            val reading = tracker.reading
            assertTrue("$bpm locked", reading.locked)
            val expected = 60_000_000.0 / bpm
            assertEquals("$bpm period", expected, reading.periodUs.toDouble(), expected * 0.015)
            assertTrue("$bpm phase ${phaseError(reading, bpm)}", phaseError(reading, bpm) < 30_000)
        }
    }

    @Test fun gridIsInTheDecksMediaTime() {
        val tracker = BeatTracker(rate)
        play(tracker, 124.0, 12.0, startUs = 93_000_000L)
        assertTrue(tracker.reading.locked)
        assertTrue(tracker.reading.beatUs > 93_000_000L)
        assertTrue(phaseError(tracker.reading, 124.0, 93_000_000L) < 30_000)
    }

    @Test fun silenceAndNoiseHaveNoBeat() {
        val quiet = BeatTracker(rate)
        quiet.begin(0)
        repeat(rate * 8) { quiet.add(0f) }
        assertFalse(quiet.reading.locked)
        assertTrue(quiet.reading.silent)
        val noise = BeatTracker(rate)
        val random = Random(3)
        noise.begin(0)
        repeat(rate * 8) { noise.add((random.nextDouble() * 2 - 1).toFloat() * 0.3f) }
        assertFalse(noise.reading.locked)
        assertFalse(noise.reading.silent)
    }

    @Test fun aJumpInMediaTimeStartsOver() {
        val tracker = BeatTracker(rate)
        play(tracker, 124.0, 10.0)
        assertTrue(tracker.reading.locked)
        play(tracker, 124.0, 1.0, startUs = 60_000_000L)
        assertFalse(tracker.reading.locked)
        // A gapless continuation keeps the lock.
        val steady = BeatTracker(rate)
        play(steady, 124.0, 10.0)
        play(steady, 124.0, 1.0, startUs = 10_000_000L)
        assertTrue(steady.reading.locked)
    }

    @Test fun aLouderSectionIsASurgeAndRaisesEnergy() {
        val tracker = BeatTracker(rate)
        play(tracker, 124.0, 10.0, gain = 0.15f)
        val quiet = tracker.reading
        assertFalse(quiet.surge)
        play(tracker, 124.0, 2.0, startUs = 10_000_000L, gain = 1f)
        val drop = tracker.reading
        assertTrue(drop.surge)
        assertTrue(drop.energy > quiet.energy + 0.3f)
        assertTrue(drop.locked)
    }

    @Test fun kickAndHatsReadAsBassAndSnap() {
        val both = BeatTracker(rate).also { play(it, 124.0, 10.0) }.reading
        val kicks = BeatTracker(rate).also { play(it, 124.0, 10.0, hatGain = 0.0) }.reading
        val hats = BeatTracker(rate).also { play(it, 124.0, 10.0, kickGain = 0.0, hatGain = 0.4) }.reading
        val all = "$both\n$kicks\n$hats"
        assertTrue(all, both.locked && both.bass > 0.6f && both.snap > 0.6f)
        // A kick's own attack is a little bright, so snap is not zero, but it reads as bass first.
        assertTrue(all, kicks.locked && kicks.bass > 0.6f && kicks.snap < kicks.bass - 0.4f)
        assertTrue(all, hats.locked && hats.snap > 0.6f && hats.bass < 0.3f)
        val noise = BeatTracker(rate)
        val random = Random(5)
        noise.begin(0)
        repeat(rate * 8) { noise.add((random.nextDouble() * 2 - 1).toFloat() * 0.3f) }
        assertTrue("${noise.reading}", noise.reading.bass == 0f && noise.reading.snap == 0f)
    }

    @Test fun aHeldVoiceRangeNoteReadsAsVoiceAndDrumsDoNot() {
        val drums = BeatTracker(rate)
        play(drums, 124.0, 8.0)
        assertTrue("${drums.reading}", drums.reading.voice < 0.3f)
        play(drums, 124.0, 2.0, startUs = 8_000_000L, tone = 0.15)
        assertTrue("${drums.reading}", drums.reading.voice > 0.6f)
        assertTrue(drums.reading.locked)
        // The phrase ends: the light retreats within a second.
        play(drums, 124.0, 1.0, startUs = 10_000_000L)
        assertTrue("${drums.reading}", drums.reading.voice < 0.3f)
    }

    @Test fun musicNowMapsTheGridToTheWallClock() {
        val reading = PulseReading(periodUs = 500_000, beatUs = 10_100_000, energy = 0.5f, silent = false)
        // Audible media time 10.0 s at elapsed 5_000 ms, normal speed.
        val now = MusicNow(reading, DeckClock(10_000_000, 5_000, 1f))
        assertEquals(listOf(10_100_000L, 10_600_000L), now.beatsFrom(5_000, 2))
        assertEquals(5_100L, now.wallMillis(10_100_000))
        assertEquals(listOf(10_600_000L), now.beatsFrom(5_101, 1))
        assertEquals(listOf(9_600_000L), now.beatsFrom(4_550, 1))
        // At 2x speed a beat 1 s of media ahead is heard 0.5 s from now, and beats are half as long.
        val fast = MusicNow(reading, DeckClock(10_000_000, 5_000, 2f))
        assertEquals(5_050L, fast.wallMillis(10_100_000))
        assertEquals(250L, fast.periodMillis)
        assertTrue(MusicNow(PulseReading(), DeckClock(0, 0, 1f)).beatsFrom(0, 3).isEmpty())
        assertTrue(abs(now.periodMillis - 500) == 0L)
    }
}
