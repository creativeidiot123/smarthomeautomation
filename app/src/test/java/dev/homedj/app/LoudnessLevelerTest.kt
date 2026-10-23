package dev.homedj.app

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessLevelerTest {
    @Test fun loudAndQuietSongsEndUpAtTheSameLoudness() {
        val loud = play(LoudnessLeveler(RATE, 2), lufs = -6.0, seconds = 20)
        val quiet = play(LoudnessLeveler(RATE, 2), lufs = -20.0, seconds = 20)
        assertEquals(LoudnessLeveler.TARGET_LUFS, loud, 0.5)
        assertEquals(LoudnessLeveler.TARGET_LUFS, quiet, 0.5)
    }

    @Test fun boostIsCappedForAVeryQuietSong() {
        val leveler = LoudnessLeveler(RATE, 2)
        play(leveler, lufs = -40.0, seconds = 20)
        assertEquals(LoudnessLeveler.MAX_BOOST_DB, leveler.gainDb, 1e-9)
    }

    @Test fun silenceLeavesGainAndSamplesAlone() {
        val leveler = LoudnessLeveler(RATE, 2)
        val frame = ShortArray(2)
        repeat(RATE * 10) {
            frame.fill(0)
            leveler.process(frame)
            assertEquals(0, frame[0].toInt())
        }
        assertEquals(0.0, leveler.gainDb, 0.0)
    }

    @Test fun boostedPeaksNeverClip() {
        val leveler = LoudnessLeveler(RATE, 2)
        play(leveler, lufs = -20.0, seconds = 10)
        assertTrue(leveler.gainDb > 5.0)
        var loudest = 0
        val frame = ShortArray(2)
        for (index in 0 until RATE * 2) {
            val click = if (index % 4_800 < 10) 32_000 else 0
            frame.fill(click.toShort())
            leveler.process(frame)
            assertTrue(frame[0] >= 0)
            loudest = maxOf(loudest, frame[0].toInt())
        }
        assertTrue("peak $loudest", loudest in 31_000..32_200)
    }

    @Test fun aSeekContinuesFromThePresentGain() {
        val leveler = LoudnessLeveler(RATE, 2, startGainDb = -8.0)
        val frame = shortArrayOf(16_000, -16_000)
        leveler.process(frame)
        assertEquals(16_000 * 10.0.pow(-8.0 / 20.0), frame[0].toDouble(), 1.0)
        assertEquals(-16_000 * 10.0.pow(-8.0 / 20.0), frame[1].toDouble(), 1.0)
    }

    /**
     * Plays a 1 kHz stereo tone at [lufs] and returns the output loudness of its last 3 seconds.
     * At 1 kHz K-weighting cancels BS.1770's -0.691 offset, so loudness is plain RMS power.
     */
    private fun play(leveler: LoudnessLeveler, lufs: Double, seconds: Int): Double {
        val amplitude = 10.0.pow(lufs / 20.0) * 32_768
        val frames = RATE * seconds
        val frame = ShortArray(2)
        var energy = 0.0
        var counted = 0
        for (index in 0 until frames) {
            val sample = (amplitude * sin(2 * PI * 1_000 * index / RATE)).toInt().toShort()
            frame.fill(sample)
            leveler.process(frame)
            if (index >= frames - RATE * 3) {
                energy += frame.sumOf { (it / 32_768.0).let { value -> value * value } }
                counted++
            }
        }
        return 10 * log10(energy / counted)
    }

    private companion object { const val RATE = 48_000 }
}
