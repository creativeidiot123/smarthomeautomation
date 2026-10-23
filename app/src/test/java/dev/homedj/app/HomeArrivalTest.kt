package dev.homedj.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeArrivalTest {
    private val scene = LightScene("movie", "Movie", mapOf(1L to LightChange(true, 20)))
    private val snapshot = LightsSnapshot(bulbs = listOf(Bulb(1, "Desk")), scenes = listOf(scene),
        automation = LightsAutomation(awayOff = true, homeOn = false, homeScene = "movie", homePlaylist = "PLhome"))

    private fun request(awayOff: Any = true, homeOn: Any = false) =
        JSONObject().put("command", "lightsPresence").put("awayOff", awayOff).put("homeOn", homeOn)

    @Test fun savesChosenSceneAndPlaylist() {
        val saved = HomeArrival.edit(snapshot.copy(automation = LightsAutomation()),
            request().put("homeScene", "movie").put("homePlaylist", "PLparty"))!!.automation
        assertEquals("movie", saved.homeScene)
        assertEquals("PLparty", saved.homePlaylist)
        assertTrue(saved.awayOff)
    }

    @Test fun olderOnePlusWithoutArrivalFieldsKeepsThem() {
        val saved = HomeArrival.edit(snapshot, request(awayOff = false))!!.automation
        assertFalse(saved.awayOff)
        assertEquals("movie", saved.homeScene)
        assertEquals("PLhome", saved.homePlaylist)
    }

    @Test fun emptyStringsClear() {
        val saved = HomeArrival.edit(snapshot, request().put("homeScene", "").put("homePlaylist", ""))!!.automation
        assertNull(saved.homeScene)
        assertNull(saved.homePlaylist)
    }

    @Test fun rejectsUnknownSceneBadPlaylistAndWrongTypes() {
        assertNull(HomeArrival.edit(snapshot, request().put("homeScene", "gone")))
        assertNull(HomeArrival.edit(snapshot, request().put("homePlaylist", "../x")))
        assertNull(HomeArrival.edit(snapshot, request().put("homeScene", 7)))
        assertNull(HomeArrival.edit(snapshot, request(homeOn = "yes")))
    }

    @Test fun restoringNeedsLightsOffWhenAway() {
        assertFalse(HomeArrival.edit(snapshot, request(awayOff = false, homeOn = true))!!.automation.homeOn)
        assertTrue(HomeArrival.edit(snapshot, request(awayOff = true, homeOn = true))!!.automation.homeOn)
    }

    @Test fun persistsAndReadsBackWhileOlderRecordsHaveNone() {
        val json = JSONObject(LightsCodec.automationJson(snapshot.automation).toString())
        assertEquals(snapshot.automation, LightsCodec.automation(json, strict = false))
        val old = LightsCodec.automation(JSONObject("""{"awayOff":true,"homeOn":true,"rules":[]}"""), strict = false)!!
        assertNull(old.homeScene)
        assertNull(old.homePlaylist)
        val bad = LightsCodec.automation(JSONObject("""{"homeScene":"","homePlaylist":"a b"}"""), strict = false)!!
        assertNull(bad.homeScene)
        assertNull(bad.homePlaylist)
    }

    @Test fun deletingTheArrivalSceneClearsIt() {
        assertNull(LightExperience.deleteScene(snapshot, "movie").automation.homeScene)
        assertEquals("movie", LightExperience.deleteScene(snapshot, "other").automation.homeScene)
    }

    @Test fun wholeAutomationSaveKeepsArrival() {
        val kept = LightExperience.preserveRuleFeatures(LightsAutomation(awayOff = false), snapshot.automation)
        assertEquals("movie", kept.homeScene)
        assertEquals("PLhome", kept.homePlaylist)
    }

    @Test fun arrivalPlaylistReplacesResumingThePausedSong() {
        assertTrue(PresencePolicy.samsungShouldResume(true, "a", playWhenReady = false, currentId = "a"))
        assertFalse(PresencePolicy.samsungShouldResume(true, "a", playWhenReady = false, currentId = "a", arrivalPlaylist = true))
    }
}
