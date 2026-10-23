package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightControlsTest {
    private fun bulb(id: Long, on: Boolean?, level: Int? = null, color: LightColor? = null) =
        Bulb(id, "Light $id", on, level, color)

    @Test fun everyPresetPassesTheOwnersValidationAndIsUnique() {
        assertTrue(LIGHT_COLORS.all { LightsPolicy.validColor(it.second) })
        assertEquals(LIGHT_COLORS.size, LIGHT_COLORS.map { it.second }.distinct().size)
        assertEquals(LIGHT_COLORS.size, LIGHT_COLORS.map { it.first }.distinct().size)
    }

    @Test fun colourNamesUsePresetsThenNearestHue() {
        assertEquals("Warm white", lightColorName(LightColor.White(2700)))
        assertEquals("3300 K", lightColorName(LightColor.White(3300)))
        assertEquals("Blue", lightColorName(LightColor.Hue(235, 100)))
        assertEquals("Blue", lightColorName(LightColor.Hue(225, 80)))
        // 350° is 10° from red across 0°, not 30° from pink.
        assertEquals("Red", lightColorName(LightColor.Hue(350, 100)))
        assertEquals("Pale green", lightColorName(LightColor.Hue(125, 20)))
    }

    @Test fun statusShowsOnlyWhatHomeDjKnows() {
        assertEquals("On · 60% · Warm white", lightStatus(bulb(1, true, 60, LightColor.White(2700))))
        assertEquals("On", lightStatus(bulb(1, true)))
        assertEquals("Off", lightStatus(bulb(1, false, 60, LightColor.White(2700))))
        assertEquals("Not set by HomeDJ yet", lightStatus(bulb(1, null)))
    }

    @Test fun summaryNeverCountsUnknownLightsAsOff() {
        assertEquals("No lights", lightsSummary(emptyList()))
        assertEquals("All on", lightsSummary(listOf(bulb(1, true), bulb(2, true))))
        assertEquals("All off", lightsSummary(listOf(bulb(1, false), bulb(2, false))))
        assertEquals("0 of 2 on", lightsSummary(listOf(bulb(1, false), bulb(2, null))))
        assertEquals("1 of 3 on", lightsSummary(listOf(bulb(1, true), bulb(2, false), bulb(3, null))))
    }

    @Test fun groupPowerIsOnWhenAnyLightIsOnAndOffOnlyWhenAllAreKnownOff() {
        assertEquals(true, groupOn(listOf(bulb(1, true), bulb(2, null))))
        assertEquals(false, groupOn(listOf(bulb(1, false), bulb(2, false))))
        assertNull(groupOn(listOf(bulb(1, false), bulb(2, null))))
        assertNull(groupOn(emptyList()))
    }

    @Test fun groupLevelAndColourComeFromLitLightsOnly() {
        val warm = LightColor.White(2700)
        val lights = listOf(bulb(1, true, 40, warm), bulb(2, true, 61, warm), bulb(3, false, 100, LightColor.Hue(0, 100)))
        assertEquals(51, groupLevel(lights))
        assertEquals(warm, groupColor(lights))
        assertNull(groupColor(lights + bulb(4, true, 10, LightColor.Hue(120, 100))))
        assertNull(groupLevel(listOf(bulb(1, false, 80), bulb(2, true))))
    }
}
