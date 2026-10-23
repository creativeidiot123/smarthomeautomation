package dev.homedj.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightScenePreviewTest {
    private val warm = LightColor.White(2700)
    private val red = LightColor.Hue(0, 100)
    private val bulbs = listOf(
        Bulb(1, "Lamp", on = true, level = 60, color = warm),
        Bulb(2, "Desk", on = false, level = 30, color = warm),
        Bulb(3, "New"),
    )
    private val movie = LightChange(true, 20, red, fadeSeconds = 5)
    private val instant = movie.copy(fadeSeconds = 0)

    @Test fun draftShowsAtOnceWithoutFade() {
        val preview = LightScenePreview()
        preview.show(mapOf(1L to movie, 2L to movie), bulbs)
        assertEquals(mapOf(1L to instant, 2L to instant), preview.take())
        // Unchanged draft (re-composition): nothing is sent again.
        preview.show(mapOf(1L to movie, 2L to movie), bulbs)
        assertEquals(emptyMap<Long, LightChange>(), preview.take())
    }

    @Test fun closingPutsEveryPreviewedLightBack() {
        val preview = LightScenePreview()
        preview.show(mapOf(1L to movie, 2L to movie), bulbs)
        preview.take()
        preview.end()
        assertEquals(mapOf(1L to LightChange(true, 60, warm), 2L to LightChange(false, 30, warm)), preview.take())
    }

    @Test fun untickedLightGoesBackAndOthersStay() {
        val preview = LightScenePreview()
        preview.show(mapOf(1L to movie, 2L to movie), bulbs)
        preview.take()
        preview.show(mapOf(1L to movie), bulbs)
        assertEquals(mapOf(2L to LightChange(false, 30, warm)), preview.take())
    }

    @Test fun captureOfStartingStatesSendsNothing() {
        val preview = LightScenePreview()
        preview.show(mapOf(1L to LightChange(true, 60, warm, 5), 2L to LightChange(false, 30, warm, 5)), bulbs)
        assertEquals(emptyMap<Long, LightChange>(), preview.take())
    }

    @Test fun newerDraftReplacingAQueuedSendStillSendsEveryChangedLight() {
        val preview = LightScenePreview()
        preview.show(mapOf(1L to movie), bulbs)
        preview.show(mapOf(1L to movie, 2L to movie.copy(level = 80)), bulbs)
        assertEquals(mapOf(1L to instant, 2L to instant.copy(level = 80)), preview.take())
    }

    @Test fun failedSendIsRetriedWithTheNextTake() {
        val preview = LightScenePreview()
        preview.show(mapOf(1L to movie), bulbs)
        preview.failed(preview.take())
        preview.end()
        // The lamp never confirmed the preview, but it may have changed: restore it anyway.
        assertEquals(mapOf(1L to LightChange(true, 60, warm)), preview.take())
    }

    @Test fun unknownStateIsNeitherSentBackNorPreviewedForEmptyChanges() {
        val preview = LightScenePreview()
        preview.show(mapOf(3L to movie, 1L to LightChange()), bulbs)
        assertEquals(mapOf(3L to instant), preview.take())
        preview.end()
        assertEquals(emptyMap<Long, LightChange>(), preview.take())
        assertNull(LightScenePreview.restore(bulbs[2]))
    }

    @Test fun reopeningBeforeRestoresSettleKeepsTheOriginalStates() {
        val preview = LightScenePreview()
        preview.show(mapOf(1L to movie), bulbs)
        preview.take()
        preview.end()
        preview.settled()
        // The snapshot still shows the preview; the base must not be re-captured from it.
        val previewed = bulbs.map { if (it.id == 1L) LightsPolicy.applied(it, instant) else it }
        preview.show(mapOf(2L to movie), previewed)
        assertEquals(bulbs.associateBy(Bulb::id), preview.base?.associateBy(Bulb::id))
        preview.end()
        assertEquals(mapOf(1L to LightChange(true, 60, warm)), preview.take())
        preview.settled()
        assertNull(preview.base)
    }

    @Test fun samsungAcceptsTheRequestItSendsAndRejectsUnsafeOnes() {
        val changes = mapOf(1L to instant, 2L to LightChange(on = false))
        val request = LightScenePreview.request(changes)
        assertEquals("lightsPreview", request.getString("command"))
        assertEquals(changes, LightScenePreview.changes(request.getJSONArray("changes"), bulbs))
        fun reject(vararg entries: JSONObject) =
            assertNull(LightScenePreview.changes(JSONArray().also { a -> entries.forEach { a.put(it) } }, bulbs))
        reject()
        reject(JSONObject().put("id", 9).put("on", true))
        reject(JSONObject().put("id", 1).put("on", true).put("fadeSeconds", 5))
        reject(JSONObject().put("id", 1).put("level", 0))
        reject(JSONObject().put("id", 1).put("on", true), JSONObject().put("id", 1).put("on", false))
        assertNull(LightScenePreview.changes(null, bulbs))
        assertNull(LightScenePreview.changes(JSONArray((1..17).map { JSONObject().put("id", 1).put("on", true) }), bulbs))
        assertTrue(LightScenePreview.changes(JSONArray().put(JSONObject().put("id", 3).put("on", true)), bulbs) != null)
    }
}
