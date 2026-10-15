package dev.homedj.app

import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBreaksTest {
    private val defaults = MusicBreakSettings()

    @Test fun defaultsAreOffAndMatchTheRequestedTimings() {
        assertTrue(defaults.isValid())
        assertFalse(defaults.enabled)
        assertEquals(15 to 35, defaults.musicMin to defaults.musicMax)
        assertEquals(10 to 25, defaults.silenceMin to defaults.silenceMax)
        assertEquals(30 to 60, defaults.longSilenceMin to defaults.longSilenceMax)
        assertEquals(45 to 75, defaults.longMusicMin to defaults.longMusicMax)
        assertEquals(5 to 10, defaults.fadeMin to defaults.fadeMax)
    }

    @Test fun jsonRoundTripsAndKeepsWireFieldNames() {
        val custom = defaults.copy(enabled = true, musicMin = 20, longSilenceChance = 35, fadeMax = 12)
        assertEquals(custom, MusicBreakSettings.fromJson(custom.toJson()))
        val json = custom.toJson()
        listOf("enabled", "musicMin", "musicMax", "longMusicMin", "longMusicMax", "longMusicChance",
            "silenceMin", "silenceMax", "longSilenceMin", "longSilenceMax", "longSilenceChance",
            "fadeMin", "fadeMax").forEach { assertTrue(it, json.has(it)) }
    }

    @Test fun missingFieldsFallBackToDefaultsAndUnknownFieldsAreIgnored() {
        val parsed = MusicBreakSettings.fromJson(JSONObject("""{"enabled":true,"future":1}"""))
        assertEquals(defaults.copy(enabled = true), parsed)
    }

    @Test fun invalidSettingsAreRejected() {
        listOf(
            defaults.copy(musicMin = 40, musicMax = 30),
            defaults.copy(silenceMin = 0),
            defaults.copy(longMusicMax = 241),
            defaults.copy(longSilenceChance = 101),
            defaults.copy(longMusicChance = -1),
            defaults.copy(fadeMin = 0),
            defaults.copy(fadeMax = 31),
        ).forEach { invalid ->
            assertFalse(invalid.toString(), invalid.isValid())
            assertNull(MusicBreakSettings.fromJson(invalid.toJson()))
        }
        assertNull(MusicBreakSettings.fromJson(JSONObject("""{"musicMin":"soon"}""").put("musicMax", 1)))
    }

    @Test fun musicSessionsStayInsideTheirRanges() {
        val random = Random(7)
        repeat(2_000) {
            val millis = MusicBreakPolicy.musicMillis(defaults, random)
            assertTrue(millis in 15 * MINUTE..35 * MINUTE || millis in 45 * MINUTE..75 * MINUTE)
        }
    }

    @Test fun silencesStayInsideTheirRanges() {
        val random = Random(11)
        repeat(2_000) {
            val millis = MusicBreakPolicy.silenceMillis(defaults, random)
            assertTrue(millis in 10 * MINUTE..25 * MINUTE || millis in 30 * MINUTE..60 * MINUTE)
        }
    }

    @Test fun chanceControlsHowOftenLongSessionsHappen() {
        val never = defaults.copy(longMusicChance = 0, longSilenceChance = 0)
        val always = defaults.copy(longMusicChance = 100, longSilenceChance = 100)
        val random = Random(3)
        repeat(500) {
            assertTrue(MusicBreakPolicy.musicMillis(never, random) <= 35 * MINUTE)
            assertTrue(MusicBreakPolicy.silenceMillis(never, random) <= 25 * MINUTE)
            assertTrue(MusicBreakPolicy.musicMillis(always, random) >= 45 * MINUTE)
            assertTrue(MusicBreakPolicy.silenceMillis(always, random) >= 30 * MINUTE)
        }
        val longCount = (1..5_000).count { MusicBreakPolicy.silenceMillis(defaults, random) >= 30 * MINUTE }
        assertTrue("about 20% long silences, got $longCount", longCount in 800..1_200)
    }

    @Test fun fadesStayBetweenFiveAndTenSeconds() {
        val random = Random(5)
        repeat(1_000) { assertTrue(MusicBreakPolicy.fadeMillis(defaults, random) in 5_000L..10_000L) }
    }

    @Test fun gainRisesSmoothlyFromSilentToFull() {
        assertEquals(0f, MusicBreakPolicy.gain(0f), 0f)
        assertEquals(1f, MusicBreakPolicy.gain(1f), 0f)
        assertEquals(1f, MusicBreakPolicy.gain(2f), 0f)
        assertEquals(0f, MusicBreakPolicy.gain(-1f), 0f)
        val steps = (0..10).map { MusicBreakPolicy.gain(it / 10f) }
        assertTrue(steps.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun aScheduledQueueResumesOnlyInsideItsOwnSlotInstance() {
        assertTrue(MusicBreakPolicy.mayResume(null, null))
        assertTrue(MusicBreakPolicy.mayResume(null, "evening@20000"))
        assertTrue(MusicBreakPolicy.mayResume("evening@20000", "evening@20000"))
        assertFalse(MusicBreakPolicy.mayResume("evening@20000", null))
        assertFalse(MusicBreakPolicy.mayResume("evening@20000", "evening@20001"))
        assertFalse(MusicBreakPolicy.mayResume("evening@20000", "night@20000"))
    }

    @Test fun breakSecondsAreZeroOnlyWithoutABreak() {
        assertEquals(0L, MusicBreakPolicy.secondsLeft(null, 1_000))
        assertEquals(60L, MusicBreakPolicy.secondsLeft(61_000, 1_000))
        assertEquals(1L, MusicBreakPolicy.secondsLeft(1_200, 1_000))
        assertEquals(1L, MusicBreakPolicy.secondsLeft(500, 1_000))
    }

    @Test fun aPersonsQueueOnABreakIsNotReplacedAtASlotStart() {
        val slot = ScheduleSlot("evening", 18, 22, 60, listOf("PLevening"))
        val onBreak = QueueState(slotKey = null, hasItems = true, playing = true)
        assertEquals(ScheduleAction.None,
            SchedulePolicy.decide(ScheduleTrigger.BOUNDARY, slot to "evening@20000", onBreak, castActive = false))
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
