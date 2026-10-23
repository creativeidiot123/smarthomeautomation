package dev.homedj.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightDanceTest {
    private val a = Bulb(1, "A", on = true, level = 60, color = LightColor.Hue(10, 90))
    private val b = Bulb(2, "B", on = true, level = 60, color = LightColor.Hue(200, 90))
    private val c = Bulb(3, "C", on = true, level = 60, color = LightColor.White(3000))
    private val hues = listOf(0, 120, 240)

    private fun beat(index: Long, energy: Float = 0.5f, surge: Boolean = false) =
        DanceBeat(index, 10_000 + index * 500, 500, energy, surge)

    @Test fun settingRoundTripsAndRejectsMalformedEdits() {
        val dance = LightDance(true, LightDanceStyle.CHILL, -120)
        assertEquals(dance, LightDanceCodec.read(JSONObject(LightDanceCodec.json(dance).toString()), strict = true))
        listOf("""{"style":"PARTY","offset":0}""", """{"on":"yes","style":"PARTY","offset":0}""",
            """{"on":true,"style":"DISCO","offset":0}""", """{"on":true,"style":"PARTY","offset":900}""",
            """{"on":true,"style":"PARTY","offset":1.5}""").forEach {
            assertNull(it, LightDanceCodec.read(JSONObject(it), strict = true))
        }
        assertNull(LightDanceCodec.read(null, strict = true))
        // Disk and snapshots are lenient: older records have none, odd values fall back.
        assertEquals(LightDance(), LightDanceCodec.read(null))
        assertEquals(LightDance(true), LightDanceCodec.read(JSONObject("""{"on":true,"style":"DISCO","offset":900}""")))
        val snapshot = LightsSnapshot(bulbs = listOf(a))
        assertNull(LightDances.edit(snapshot, JSONObject().put("command", "lightsDance")))
        assertEquals(dance, LightDances.edit(snapshot, JSONObject().put("dance", LightDanceCodec.json(dance)))?.dance)
    }

    @Test fun snapshotCarriesTheSettingAndOlderSnapshotsReadAsOff() {
        val snapshot = LightsSnapshot(bulbs = listOf(a), dance = LightDance(true, LightDanceStyle.PARTY, 200))
        assertEquals(snapshot.dance, LightsCodec.snapshot(JSONObject(LightsCodec.snapshotJson(snapshot).toString())).dance)
        val older = LightsCodec.snapshotJson(snapshot).also { it.getJSONObject("experience").remove("dance") }
        assertEquals(LightDance(), LightsCodec.snapshot(older).dance)
    }

    @Test fun onlyLitLightsThatCanChangeDance() {
        val off = a.copy(id = 4, on = false)
        val unknown = a.copy(id = 5, on = null)
        val plain = a.copy(id = 6, reading = LightReading(1, dimmable = false, color = false, white = false))
        assertEquals(listOf(1L, 2L, 3L), LightDances.dancers(listOf(c, plain, off, b, unknown, a)).map(Bulb::id))
    }

    @Test fun partyHitChasesRoundTheLightsAndTheLastOneFades() {
        val bulbs = listOf(a, b, c)
        (0L until 6L).forEach { index ->
            val cues = LightDances.beat(LightDanceStyle.PARTY, beat(index), bulbs, hues)
            val hit = cues.first()
            assertEquals(bulbs[(index % 3).toInt()].id, hit.bulb)
            assertEquals(beat(index).at, hit.at)
            assertEquals(0, hit.light.millis)
            assertEquals(73, hit.light.level)
            val fade = cues[1]
            assertEquals(bulbs[Math.floorMod(index - 1, 3L).toInt()].id, fade.bulb)
            assertEquals(18, fade.light.level)
            assertEquals(450, fade.light.millis)
        }
    }

    @Test fun aSingleLightHitsAndFallsWithinEachBeat() {
        val cues = LightDances.beat(LightDanceStyle.PARTY, beat(1), listOf(a), hues)
        assertEquals(2, cues.size)
        assertEquals(cues[0].at + 200, cues[1].at)
        assertTrue(cues[1].light.level!! < cues[0].light.level!!)
        assertTrue(cues[1].at + cues[1].light.millis < beat(2).at)
    }

    @Test fun partyStaysWithinWhatMatterBulbsKeepUpWith() {
        val bulbs = listOf(a, b, c)
        listOf(false, true).forEach { surge ->
            val sends = (0L until 32L).sumOf { LightDances.beat(LightDanceStyle.PARTY, beat(it, surge = surge), bulbs, hues).size }
            assertTrue("$surge $sends", sends <= 32 * 3)
        }
        val chill = (0L until 32L).sumOf { LightDances.beat(LightDanceStyle.CHILL, beat(it), bulbs, hues).size }
        assertTrue("$chill", chill <= 32 * 2)
    }

    @Test fun colourChangesPerBarAndFasterOnASurge() {
        val bulbs = listOf(a, b)
        fun colours(surge: Boolean) = (0L until 8L).flatMap { index ->
            LightDances.beat(LightDanceStyle.PARTY, beat(index, surge = surge), bulbs, hues)
                .filter { it.bulb == a.id && it.light.color != null }.map { index to it.light }
        }
        assertEquals(listOf(0L, 4L), colours(false).map { it.first })
        assertEquals(listOf(0L, 2L, 4L, 6L), colours(true).map { it.first })
        assertTrue(colours(true).all { it.second.millis == 0 })
        // Neighbours never take the same colour on their change.
        val first = LightDances.beat(LightDanceStyle.PARTY, beat(0), bulbs, hues).first { it.bulb == a.id }.light.color
        val second = LightDances.beat(LightDanceStyle.PARTY, beat(1), bulbs, hues).first { it.bulb == b.id && it.light.color != null }.light.color
        assertTrue(first != second)
    }

    @Test fun chillBreathesOutOfStepAndFollowsLoudness() {
        val bulbs = listOf(a, b)
        val even = LightDances.beat(LightDanceStyle.CHILL, beat(0, energy = 1f), bulbs, hues)
        val odd = LightDances.beat(LightDanceStyle.CHILL, beat(1, energy = 1f), bulbs, hues)
        assertEquals(listOf(a.id), even.filter { it.light.level != null }.map { it.bulb })
        assertEquals(listOf(b.id), odd.filter { it.light.level != null }.map { it.bulb })
        val quiet = LightDances.beat(LightDanceStyle.CHILL, beat(0, energy = 0f), bulbs, hues)
        assertTrue(quiet.first().light.level!! < even.first().light.level!!)
        assertTrue(even.all { it.light.millis >= 1_000 })
    }

    @Test fun neverTurnsALightOnAndSkipsWhatItCannotShow() {
        val whiteOnly = a.copy(reading = LightReading(1, dimmable = true, color = false, white = true))
        val cues = (0L until 8L).flatMap { LightDances.beat(LightDanceStyle.PARTY, beat(it), listOf(whiteOnly), hues) }
        assertTrue(cues.isNotEmpty())
        assertTrue(cues.all { it.light.color == null })
        val commands = (0L until 8L).flatMap { LightDances.beat(LightDanceStyle.PARTY, beat(it), listOf(a, b), hues) }
            .flatMap { it.light.commands() }
        assertTrue(commands.none { it.cluster == MatterIds.ON_OFF })
        assertTrue(commands.filter { it.cluster == MatterIds.LEVEL_CONTROL }.all { it.command == MatterIds.MOVE_TO_LEVEL })
        assertNull(LightDances.fit(a.copy(on = false), VariedLight(50, null, 0)))
        assertNotNull(LightDances.fit(a, VariedLight(50, null, 0)))
    }

    @Test fun noArtworkFallsBackToVividDefaults() {
        val cue = LightDances.beat(LightDanceStyle.PARTY, beat(0), listOf(a), emptyList()).first()
        assertEquals(LightColor.Hue(LightDances.DEFAULT_HUES[0], 100), cue.light.color)
    }

    @Test fun artworkHuesPickTheSongsVividColours() {
        val red = 0xFFE01010.toInt()
        val blue = 0xFF1030E0.toInt()
        val grey = 0xFF808080.toInt()
        val split = IntArray(400) { if (it < 200) red else if (it < 350) blue else grey }
        val hues = artworkHues(split)
        assertEquals(2, hues.size)
        assertTrue(hues[0] <= 5 || hues[0] >= 355)
        assertEquals(231.0, hues[1].toDouble(), 5.0)
        assertTrue(artworkHues(IntArray(400) { grey }).isEmpty())
        assertTrue(artworkHues(IntArray(400) { 0xFF050505.toInt() }).isEmpty())
        assertEquals(3, artworkHues(IntArray(400) { red }).size)
    }
}
