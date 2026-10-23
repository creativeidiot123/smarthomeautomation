package dev.homedj.app

import kotlin.math.abs
import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightVariationTest {
    private val subtle = LightVariation(enabled = true)
    private val wild = LightVariation(enabled = true, hue = 75, saturation = 75, brightness = 75, speed = 75)
    private val colour = Bulb(1, "Desk", on = true, level = 50, color = LightColor.Hue(355, 95))
    private val white = Bulb(2, "Floor", on = true, level = 80, color = LightColor.White(6400))
    private val bulbs = listOf(colour, white)

    @Test fun codesRoundTripAndRejectMalformedValues() {
        listOf(LightVariation(), subtle, wild, LightVariation(true, 0, 100, 1, 0), LightVariation(false, 100, 100, 100, 100))
            .forEach { assertEquals(it, LightVariationCodec.read(LightVariationCodec.code(it))) }
        // Every value an older build could save (it always kept one channel on) reads back the same.
        (0..63).filter { it and 56 != 0 }.forEach { assertEquals(it, LightVariationCodec.legacy(requireNotNull(LightVariationCodec.fromLegacy(it)))) }
        assertEquals(63, LightVariationCodec.legacy(wild))
        assertEquals(56, LightVariationCodec.legacy(LightVariation()))
        assertNull(LightVariationCodec.read(-1))
        assertNull(LightVariationCodec.read(1 shl 29))
        assertNull(LightVariationCodec.read(101 shl 1))
        assertNull(LightVariationCodec.fromLegacy(64))
        assertNull(LightVariationCodec.read(JSONObject(), "v"))
        listOf<Any>(64, 1.5, "63", true).forEach { value ->
            assertFalse(value.toString(), requireNotNull(LightVariationCodec.read(JSONObject().put("v", value), "v")).valid)
        }
        listOf<Any>(1 shl 29, 101 shl 22, -1, 1.5, "63", true).forEach { value ->
            assertFalse(value.toString(), requireNotNull(LightVariationCodec.read(JSONObject().put("v", 63).put("v2", value), "v")).valid)
        }
        assertFalse(LightVariation(true, hue = 0, saturation = 0, brightness = 0).valid)
        assertTrue(LightVariation(false, hue = 0, saturation = 0, brightness = 0).valid)
        assertFalse(LightVariation(true, hue = 101).valid)
        assertFalse(LightVariation(true, speed = -1).valid)
    }

    @Test fun olderBuildsReadTheNearestTwoStepSetting() {
        // Older builds saved and sent only the 6-bit value: Subtle/High, Slow/Fast and channel bits.
        assertEquals(LightVariation(true, 75, 0, 75, 25), LightVariationCodec.read(JSONObject().put("v", 1 or 2 or 8 or 32), "v"))
        // Two-step values need no extra field; any other value adds `<key>2` and keeps the 6-bit one for old builds.
        assertFalse(LightVariationCodec.put(JSONObject(), "v", wild).has("v2"))
        val custom = LightVariation(true, hue = 90, saturation = 0, brightness = 10, speed = 40)
        val json = LightVariationCodec.put(JSONObject(), "variation", custom)
        assertEquals(1 or 2 or 8 or 32, json.getInt("variation"))
        assertEquals(custom, LightVariationCodec.read(JSONObject(json.toString()), "variation"))
        assertEquals(LightVariation(true, 75, 0, 75, 25), LightVariationCodec.fromLegacy(json.getInt("variation")))
    }

    @Test fun subtleSlowStepsStayCloseToTheRecordedBase() {
        val random = Random(7)
        var drift = VariationDrift()
        repeat(500) {
            val step = requireNotNull(LightVariations.step(colour, subtle, random, drift))
            val level = requireNotNull(step.level)
            val hue = step.color as LightColor.Hue
            assertTrue(level in 45..55)
            assertTrue(hueDistance(hue.degrees, 355) <= 12)
            assertTrue(hue.saturationPercent in 87..100)
            assertTrue(step.millis in 9_500..13_000)
            assertFalse(step.restore)
            drift = step.drift
        }
    }

    @Test fun eachStepWalksOnFromTheLastInsteadOfJumpingAcrossTheRange() {
        val random = Random(9)
        var previous = VariationDrift()
        val hues = mutableSetOf<Int>()
        repeat(500) {
            val step = requireNotNull(LightVariations.step(colour, wild, random, previous))
            // 75 %: hue within +-45 degrees, at most 22 per step; level within +-18 (50 x 37 %), at most 9 per step.
            assertTrue(abs(step.drift.hue) <= 45 && abs(step.drift.hue - previous.hue) <= 22)
            assertTrue(abs(step.drift.level) <= 18 && abs(step.drift.level - previous.level) <= 9)
            hues += step.drift.hue
            previous = step.drift
        }
        // The walk still covers the whole range over time.
        assertTrue(hues.min() <= -40 && hues.max() >= 40)
        // A drift left over from a wider setting is pulled inside the new range.
        val narrow = requireNotNull(LightVariations.step(colour, subtle, random, VariationDrift(hue = 45, level = 18)))
        assertTrue(abs(narrow.drift.hue) <= 12 && abs(narrow.drift.level) <= 5)
    }

    @Test fun highFastStepsMoveFurtherAndFasterWithinDeviceRanges() {
        val random = Random(11)
        var drift = VariationDrift()
        val steps = List(500) { requireNotNull(LightVariations.step(white, wild, random, drift)).also { drift = it.drift } }
        steps.forEach { step ->
            assertTrue(requireNotNull(step.level) in 1..100)
            assertTrue((step.color as LightColor.White).kelvin in LightsPolicy.KELVIN)
            assertTrue(step.millis in 3_000..4_100)
        }
        assertTrue(steps.any { abs(requireNotNull(it.level) - 80) > 12 })
        assertTrue(steps.any { 6400 - (it.color as LightColor.White).kelvin > 300 })
    }

    @Test fun speedRunsFromSlowToFastAndAmountsScaleTheDrift() {
        assertEquals(20_000, LightVariations.stepMillis(0))
        assertEquals(2_000, LightVariations.stepMillis(100))
        assertTrue((0 until 100).all { LightVariations.stepMillis(it) > LightVariations.stepMillis(it + 1) })
        val random = Random(4)
        var drift = VariationDrift()
        val widest = List(300) { requireNotNull(LightVariations.step(colour, LightVariation(true, 100, 0, 0), random, drift))
            .also { drift = it.drift } }
        val faintest = List(300) { requireNotNull(LightVariations.step(colour, LightVariation(true, 1, 0, 0), random)) }
        assertTrue(widest.all { abs(it.drift.hue) <= 60 } && widest.any { abs(it.drift.hue) >= 50 })
        assertTrue(faintest.all { abs(it.drift.hue) <= 1 })
    }

    @Test fun onlyChosenAndSupportedChannelsChange() {
        val random = Random(3)
        val brightnessOnly = LightVariation(true, hue = 0, saturation = 0)
        // Channels at 0 % are not sent at all, so the bulb keeps its colour.
        repeat(50) { assertNull(requireNotNull(LightVariations.step(colour, brightnessOnly, random)).color) }
        val hueOnly = LightVariation(true, saturation = 0, brightness = 0)
        repeat(50) {
            val step = requireNotNull(LightVariations.step(colour, hueOnly, random))
            assertNull(step.level)
            assertEquals(95, (step.color as LightColor.Hue).saturationPercent)
        }
        // Saturation has no meaning for white light, and a bulb without colour support keeps its colour.
        assertNull(LightVariations.step(white, LightVariation(true, hue = 0, brightness = 0), random))
        val plain = colour.copy(reading = LightReading(1, dimmable = false, color = false, white = false))
        assertNull(LightVariations.step(plain, wild, random))
    }

    @Test fun offUnknownOrDisabledLightsAreNeverVaried() {
        val random = Random(1)
        assertNull(LightVariations.step(colour.copy(on = false), wild, random))
        assertNull(LightVariations.step(colour.copy(on = null), wild, random))
        assertNull(LightVariations.step(colour.copy(level = null, color = null), wild, random))
        assertNull(LightVariations.step(colour, wild.copy(enabled = false), random))
    }

    @Test fun aVariedLightReturnsToItsRecordOnceWhenVariationStops() {
        val random = Random(5)
        val restore = requireNotNull(LightVariations.plan(colour, LightVariation(), varied = true, random))
        assertEquals(VariedLight(50, LightColor.Hue(355, 95), 2_000, restore = true), restore)
        assertNull(LightVariations.plan(colour, LightVariation(), varied = false, random))
        // Turned off while varied: restored in place (ExecuteIfOff), so it comes back on unvaried.
        assertTrue(requireNotNull(LightVariations.plan(colour.copy(on = false), wild, varied = true, random)).restore)
        assertFalse(requireNotNull(LightVariations.plan(colour, wild, varied = true, random)).restore)
    }

    @Test fun stepsNeverSwitchPowerAndSkipBulbsThatAreOff() {
        val commands = VariedLight(50, LightColor.Hue(0, 100), 3_000).commands()
        assertEquals(listOf(MatterIds.MOVE_TO_HUE_AND_SATURATION, MatterIds.MOVE_TO_LEVEL), commands.map { it.command })
        // Hue 0, saturation 254, 30 tenths, ExecuteIfOff mask set, override clear.
        assertArrayEquals(byteArrayOf(0x15, 0x24, 0, 0, 0x24, 1, 0xFE.toByte(), 0x24, 2, 30, 0x24, 3, 1, 0x24, 4, 0, 0x18),
            commands[0].tlv)
        // MoveToLevel (no OnOff): level 127, 30 tenths, mask set, override clear.
        assertArrayEquals(byteArrayOf(0x15, 0x24, 0, 127, 0x24, 1, 30, 0x24, 2, 1, 0x24, 3, 0, 0x18), commands[1].tlv)
        val restore = VariedLight(50, null, 2_000, restore = true).commands().single()
        assertEquals(MatterIds.MOVE_TO_LEVEL, restore.command)
        assertArrayEquals(byteArrayOf(0x15, 0x24, 0, 127, 0x24, 1, 20, 0x24, 2, 1, 0x24, 3, 1, 0x18), restore.tlv)
    }

    @Test fun allLightVariationIsTheLatestChangeForEveryLight() {
        val snapshot = LightsSnapshot(bulbs)
        val own = requireNotNull(LightVariations.edit(snapshot, request(wild, id = 1)))
        assertEquals(wild, own.variationOf(1))
        assertEquals(LightVariation(), own.variationOf(2))
        val all = requireNotNull(LightVariations.edit(own, request(subtle)))
        assertEquals(LightsVariation(subtle), all.variation)
        val again = requireNotNull(LightVariations.edit(all, request(wild, id = 2)))
        val cleared = requireNotNull(LightVariations.edit(again, JSONObject().put("id", 2).put("clear", true)))
        assertEquals(subtle, cleared.variationOf(2))
    }

    @Test fun aLightUsesItsOwnThenItsGroupsThenTheAllLightVariation() {
        val kitchen = LightsGroup("kitchen", "Kitchen", listOf(1L, 2L), variation = wild)
        val snapshot = LightsSnapshot(bulbs, configuration = LightsConfiguration(listOf(kitchen)),
            variation = LightsVariation(subtle, mapOf(2L to LightVariation())))
        assertEquals(wild, snapshot.variationOf(1))
        assertEquals(LightVariation(), snapshot.variationOf(2))
        val followsAll = snapshot.copy(configuration = LightsConfiguration(listOf(kitchen.copy(variation = null))))
        assertEquals(subtle, followsAll.variationOf(1))
    }

    @Test fun groupVariationIsTheLatestChangeForItsLights() {
        val kitchen = LightsGroup("kitchen", "Kitchen", listOf(1L))
        val juggling = LightJuggle(30, setOf(1L, 2L, 3L))
        val snapshot = LightsSnapshot(bulbs, configuration = LightsConfiguration(listOf(kitchen)),
            variation = LightsVariation(subtle, mapOf(1L to LightVariation(), 2L to subtle), juggling))
        val set = requireNotNull(LightVariations.edit(snapshot, groupRequest(wild)))
        assertEquals(wild, set.configuration.groups.single().variation)
        assertEquals(mapOf(2L to subtle), set.variation.individual)
        assertEquals(setOf(2L, 3L), set.variation.juggle?.bulbs)
        assertEquals(wild, set.variationOf(1))
        // A light's own value set afterwards is newer than its group's.
        val own = requireNotNull(LightVariations.edit(set, request(LightVariation(), id = 1)))
        assertEquals(LightVariation(), own.variationOf(1))
        val cleared = requireNotNull(LightVariations.edit(set, groupRequest(null)))
        assertNull(cleared.configuration.groups.single().variation)
        assertEquals(subtle, cleared.variationOf(1))
        // The all-light value is newer than every group's and light's own.
        val all = requireNotNull(LightVariations.edit(own, request(LightVariation())))
        assertNull(all.configuration.groups.single().variation)
        assertEquals(LightsVariation(LightVariation()), all.variation)
    }

    @Test fun invalidGroupVariationEditsAreRejected() {
        val snapshot = LightsSnapshot(bulbs, configuration = LightsConfiguration(listOf(LightsGroup("kitchen", "Kitchen", listOf(1L)))))
        assertNull(LightVariations.edit(snapshot, groupRequest(wild).put("groupId", "missing")))
        assertNull(LightVariations.edit(snapshot, groupRequest(wild).put("variation", 7)))
        assertNull(LightVariations.edit(snapshot, groupRequest(wild).put("variation", "63")))
        assertNull(LightVariations.edit(snapshot, JSONObject().put("command", "lightsGroupVariation").put("groupId", "kitchen")))
    }

    @Test fun lightsJoiningAGroupFollowItsVariation() {
        val current = LightsVariation(subtle, mapOf(1L to wild, 2L to wild), LightJuggle(30, setOf(1L, 2L, 3L)))
        val joined = LightVariations.joined(current, listOf(1L))
        assertEquals(mapOf(2L to wild), joined.individual)
        assertEquals(setOf(2L, 3L), joined.juggle?.bulbs)
    }

    @Test fun groupVariationRoundTripsAndOlderGroupsHaveNone() {
        val group = LightsGroup("kitchen", "Kitchen", listOf(1L), variation = wild)
        val json = LightsGroupsCodec.groupJson(group)
        assertEquals(63, json.getInt("v"))
        assertEquals(group, LightsGroupsCodec.group(JSONObject(json.toString())))
        assertNull(LightsGroupsCodec.group(JSONObject(json.toString()).apply { remove("v") })?.variation)
        assertNull(LightsGroupsCodec.group(JSONObject(json.toString()).put("v", 7)))
        assertNull(LightsGroupsCodec.group(JSONObject(json.toString()).put("v", "63")))
        val snapshot = LightsSnapshot(bulbs, configuration = LightsConfiguration(listOf(group)))
        assertEquals(snapshot, LightsCodec.snapshot(JSONObject(LightsCodec.snapshotJson(snapshot).toString())))
    }

    @Test fun invalidVariationEditsAreRejected() {
        val snapshot = LightsSnapshot(bulbs)
        assertNull(LightVariations.edit(snapshot, request(wild, id = 99)))
        assertNull(LightVariations.edit(snapshot, JSONObject().put("variation", 99)))
        assertNull(LightVariations.edit(snapshot, JSONObject().put("variation", "63")))
        // On, high and fast, but nothing to vary.
        assertNull(LightVariations.edit(snapshot, JSONObject().put("variation", 7)))
        assertNull(LightVariations.edit(snapshot, JSONObject()))
    }

    @Test fun scenesSetEachLightsVariationAndPerLightOverridesWin() {
        val scene = LightScene("party", "Party", mapOf(1L to LightChange(on = true), 2L to LightChange(on = true)),
            subtle, mapOf(2L to wild))
        val applied = LightVariations.applyScene(LightsVariation(), scene, listOf(1L, 2L))
        assertEquals(mapOf(1L to subtle, 2L to wild), applied.individual)
        val legacy = scene.copy(variation = null, variations = emptyMap())
        assertEquals(LightsVariation(), LightVariations.applyScene(LightsVariation(), legacy, listOf(1L, 2L)))
    }

    @Test fun sceneVariationRoundTripsAndOlderControllersCannotEraseIt() {
        val scene = LightScene("party", "Party", mapOf(1L to LightChange(on = true), 2L to LightChange(level = 30)),
            subtle, mapOf(2L to wild))
        val json = LightExperienceCodec.sceneJson(scene)
        assertEquals(scene, LightExperienceCodec.scene(JSONObject(json.toString())))
        val saved = requireNotNull(LightExperience.saveScene(LightsSnapshot(bulbs), scene))
        // An older Controller resends the scene without the fields it never knew.
        json.remove("variation")
        val legacy = requireNotNull(LightExperienceCodec.scene(json.put("name", "Party 2")))
        assertNull(legacy.variation)
        val kept = requireNotNull(LightExperience.saveScene(saved, legacy)).scenes.single()
        assertEquals("Party 2", kept.name)
        assertEquals(subtle, kept.variation)
        assertEquals(mapOf(2L to wild), kept.variations)
        // A current Controller can explicitly turn it off.
        val off = requireNotNull(LightExperience.saveScene(saved, scene.copy(variation = LightVariation(), variations = emptyMap())))
        assertEquals(LightVariation(), off.scenes.single().variation)
        assertTrue(off.scenes.single().variations.isEmpty())
        assertNull(LightExperienceCodec.scene(JSONObject(LightExperienceCodec.sceneJson(scene).toString()).put("variation", "loud")))
        assertNull(LightExperienceCodec.scene(JSONObject(LightExperienceCodec.sceneJson(scene).toString()).put("variation", 7)))
        assertNull(LightExperience.saveScene(LightsSnapshot(bulbs), scene.copy(variations = mapOf(9L to wild))))
    }

    @Test fun snapshotVariationRoundTripsAndDropsForgottenLights() {
        val snapshot = LightsSnapshot(bulbs, variation = LightsVariation(subtle, mapOf(2L to wild)))
        assertEquals(snapshot, LightsCodec.snapshot(JSONObject(LightsCodec.snapshotJson(snapshot).toString())))
        val older = LightsCodec.snapshotJson(snapshot).also { it.getJSONObject("experience").remove("variation") }
        assertEquals(LightsVariation(), LightsCodec.snapshot(older).variation)
        val forgotten = LightsCodec.snapshotJson(snapshot.copy(bulbs = listOf(colour)))
        assertEquals(LightsVariation(subtle), LightsCodec.snapshot(forgotten).variation)
        assertEquals(LightsVariation(subtle), LightVariations.withoutBulb(snapshot.variation, 2))
    }

    @Test fun unreachableLightsBackOffWithABound() {
        assertEquals(30_000L, LightVariations.backoffMillis(1))
        assertEquals(60_000L, LightVariations.backoffMillis(2))
        assertEquals(480_000L, LightVariations.backoffMillis(5))
        assertEquals(480_000L, LightVariations.backoffMillis(40))
    }

    private fun request(variation: LightVariation, id: Long? = null) =
        LightVariationCodec.put(JSONObject(), "variation", variation).also { json -> id?.let { json.put("id", it) } }

    private fun groupRequest(variation: LightVariation?) = JSONObject().put("command", "lightsGroupVariation")
        .put("groupId", "kitchen").also { json ->
            if (variation == null) json.put("clear", true) else LightVariationCodec.put(json, "variation", variation)
        }

    private fun hueDistance(a: Int, b: Int) = minOf(Math.floorMod(a - b, 360), Math.floorMod(b - a, 360))
}
