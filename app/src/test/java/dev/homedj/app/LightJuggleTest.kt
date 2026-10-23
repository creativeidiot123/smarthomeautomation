package dev.homedj.app

import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightJuggleTest {
    private val red = LightColor.Hue(0, 100)
    private val blue = LightColor.Hue(240, 100)
    private val green = LightColor.Hue(120, 100)
    private val on = LightVariation(enabled = true)
    private val bulbs = listOf(
        Bulb(1, "Lamp", on = true, level = 60, color = red),
        Bulb(2, "Desk", on = true, level = 60, color = blue),
        Bulb(3, "Shelf", on = true, level = 60, color = green),
        Bulb(4, "Hall", on = false, level = 60, color = red),
    )
    private val scene = LightScene("s1", "Party", mapOf(1L to LightChange(true, 60, red), 2L to LightChange(true, 60, blue),
        3L to LightChange(true, 60, green)), on, juggle = 30)
    private fun snapshot(variation: LightsVariation) = LightsSnapshot(bulbs, scenes = listOf(scene), variation = variation)

    @Test fun sceneJuggleRoundTripsAndRejectsBadIntervals() {
        assertEquals(scene, LightExperienceCodec.scene(LightExperienceCodec.sceneJson(scene)))
        assertNull(LightExperienceCodec.scene(LightExperienceCodec.sceneJson(scene.copy(juggle = null)))?.juggle)
        listOf(-1, 5, 3601).forEach { seconds ->
            assertNull(LightExperienceCodec.scene(LightExperienceCodec.sceneJson(scene).put("j", seconds)))
        }
        assertTrue(LightExperience.valid(scene.copy(juggle = 0), bulbs))
    }

    @Test fun olderControllersKeepTheJuggleTheyNeverReceivedButNewOnesCanTurnItOff() {
        val saved = requireNotNull(LightExperience.saveScene(LightsSnapshot(bulbs), scene))
        assertEquals(30, requireNotNull(LightExperience.saveScene(saved, scene.copy(juggle = null))).scenes.single().juggle)
        assertEquals(0, requireNotNull(LightExperience.saveScene(saved, scene.copy(juggle = 0))).scenes.single().juggle)
    }

    @Test fun applyingAJuggleSceneStartsOneJuggleAndOtherChangesWin() {
        val ids = listOf(1L, 2L, 3L)
        val started = LightVariations.applyScene(LightsVariation(), scene, ids)
        assertEquals(LightJuggle(30, ids.toSet()), started.juggle)
        // Without variation, or another scene for two of its lights: the juggle ends (fewer than two left).
        assertNull(LightVariations.applyScene(LightsVariation(), scene.copy(variation = LightVariation()), ids).juggle)
        assertNull(LightVariations.applyScene(started, scene.copy(juggle = 0), listOf(1L, 2L)).juggle)
        assertEquals(setOf(2L, 3L), LightVariations.applyScene(started, scene.copy(juggle = 0), listOf(1L)).juggle?.bulbs)
        // A scene from an older build leaves variation, and so the juggle, alone.
        assertEquals(started.juggle, LightVariations.applyScene(started, scene.copy(variation = null), listOf(1L)).juggle)
        val withJuggle = snapshot(started)
        val own = requireNotNull(LightVariations.edit(withJuggle, JSONObject().put("variation", 57).put("id", 1)))
        assertEquals(setOf(2L, 3L), own.variation.juggle?.bulbs)
        assertNull(requireNotNull(LightVariations.edit(withJuggle, JSONObject().put("variation", 57))).variation.juggle)
        assertEquals(setOf(1L, 3L), LightVariations.withoutBulb(started, 2).juggle?.bulbs)
    }

    @Test fun onlyLitColouredVaryingLightsJuggle() {
        val juggle = LightJuggle(30, setOf(1, 2, 3, 4))
        assertEquals(listOf(1L, 2L, 3L), LightJuggles.participants(snapshot(LightsVariation(on, juggle = juggle))))
        val ownOff = LightsVariation(on, mapOf(2L to LightVariation(), 3L to LightVariation()), juggle)
        assertEquals(emptyList<Long>(), LightJuggles.participants(snapshot(ownOff)))
        assertEquals(emptyList<Long>(), LightJuggles.participants(snapshot(LightsVariation(on))))
    }

    @Test fun everyShuffleMovesEveryLightToAnotherColour() {
        val random = Random(3)
        val ids = listOf(1L, 2L, 3L)
        var assignment = emptyMap<Long, Long>()
        repeat(200) {
            val next = LightJuggles.shuffle(assignment, ids, random)
            assertEquals(ids.toSet(), next.values.toSet())
            ids.forEach { id -> assertNotEquals(assignment[id] ?: id, next[id]) }
            assignment = next
        }
        assertEquals(mapOf(1L to 2L, 2L to 1L), LightJuggles.shuffle(emptyMap(), listOf(1L, 2L), random))
        assertEquals(emptyMap<Long, Long>(), LightJuggles.shuffle(emptyMap(), listOf(1L), random))
    }

    @Test fun juggleStepReadsTheSourceColourFromTheRecordItIsSentUnder() {
        val levelOnly = LightVariation(enabled = true, hue = 0, saturation = 0)
        // A scene turned Desk green after the runner's snapshot (Desk blue) was taken.
        val afterScene = LightsSnapshot(bulbs.map { if (it.id == 2L) it.copy(color = green) else it },
            variation = LightsVariation(levelOnly))
        assertEquals(green, LightVariations.next(afterScene, 1, varied = true, Random(1), source = 2)?.color)
        // A source showing the light's own colour, or none: the own colour goes back only after a swap.
        assertEquals(red, LightVariations.next(afterScene, 1, true, Random(1), source = 4, swapped = true)?.color)
        assertNull(LightVariations.next(afterScene, 1, true, Random(1))?.color)
        assertNull(LightVariations.next(afterScene, 9, true, Random(1), source = 2))
    }

    @Test fun lightsShowTheirSourcesColourOnlyWhenTheyCanShowIt() {
        val s = snapshot(LightsVariation(on))
        assertEquals(blue, LightJuggles.color(s, mapOf(1L to 2L), 1))
        assertNull(LightJuggles.color(s, mapOf(1L to 1L), 1))
        assertNull(LightJuggles.color(s, mapOf(1L to 4L), 1))
        val whiteOnly = s.copy(bulbs = s.bulbs.map { if (it.id == 1L) it.copy(reading = LightReading(1, true, false, true)) else it })
        assertNull(LightJuggles.color(whiteOnly, mapOf(1L to 2L), 1))
    }

    @Test fun variationDriftsAroundTheJuggledColourAndSwapsBackWhenItStops() {
        val lamp = bulbs.first()
        val levelOnly = LightVariation(enabled = true, hue = 0, saturation = 0)
        val step = requireNotNull(LightVariations.plan(lamp, on, varied = true, Random(1), juggled = blue, sendColor = true))
        val hue = (step.color as LightColor.Hue).degrees
        assertTrue("$hue", hue in 230..250)
        // Colour channels off: the swap still shows, and the light's own colour comes back afterwards.
        assertEquals(blue, LightVariations.plan(lamp, levelOnly, true, Random(1), juggled = blue, sendColor = true)?.color)
        assertEquals(red, LightVariations.plan(lamp, levelOnly, true, Random(1), sendColor = true)?.color)
        assertNull(LightVariations.plan(lamp, levelOnly, true, Random(1))?.color)
        // Variation off: the restore goes to the record, never the juggled colour.
        assertEquals(red, LightVariations.plan(lamp, LightVariation(), true, Random(1), juggled = blue, sendColor = true)?.color)
    }

    @Test fun snapshotJuggleRoundTripsAndDropsUnpairedLights() {
        val own = mapOf(1L to on, 2L to on, 9L to on)
        val variation = LightsVariation(on, own, LightJuggle(60, setOf(1, 2, 9)))
        assertEquals(LightJuggle(60, setOf(1, 2)), LightVariationCodec.read(LightVariationCodec.json(variation), bulbs).juggle)
        assertNull(LightVariationCodec.read(LightVariationCodec.json(variation.copy(juggle = LightJuggle(60, setOf(1, 9)))), bulbs).juggle)
        assertNull(LightVariationCodec.read(LightVariationCodec.json(variation.copy(juggle = LightJuggle(5, setOf(1, 2)))), bulbs).juggle)
        // A juggle is only ever stored for lights with their own entry (applying the scene writes them).
        val applied = LightVariations.applyScene(LightsVariation(), scene, listOf(1L, 2L, 3L))
        assertEquals(applied, LightVariationCodec.read(LightVariationCodec.json(applied), bulbs))
    }
}
