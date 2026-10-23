package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneEachLightTest {
    private val red = LightChange(true, 80, LightColor.Hue(0, 100))
    private val blue = LightChange(true, 30, LightColor.Hue(240, 100))
    private val off = LightChange(false, 50, LightColor.White(2700))

    @Test fun draftsRoundTripWithoutTheSharedFade() {
        val drafts = mapOf(1L to red, 2L to blue.copy(fadeSeconds = 30), 3L to off)
        assertEquals(mapOf(1L to red, 2L to blue, 3L to off), SceneEachLight.decode(SceneEachLight.encode(drafts)))
        assertEquals(emptyMap<Long, LightChange>(), SceneEachLight.decode(""))
        assertEquals(emptyMap<Long, LightChange>(), SceneEachLight.decode("[{\"on\":true}]"))
    }

    @Test fun eachLightStartsFromWhatTheEditorShows() {
        val shared = LightChange(true, 40, LightColor.White(2700), fadeSeconds = 5)
        val seeded = SceneEachLight.seed(listOf(1L, 2L, 3L), mapOf(1L to red.copy(fadeSeconds = 5), 2L to LightChange()), shared)
        // Light 2 had nothing to set and light 3 nothing shown: both start from the shared settings.
        assertEquals(mapOf(1L to red, 2L to shared.copy(fadeSeconds = 0), 3L to shared.copy(fadeSeconds = 0)), seeded)
    }
}
