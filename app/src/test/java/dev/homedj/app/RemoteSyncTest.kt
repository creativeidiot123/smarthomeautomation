package dev.homedj.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSyncTest {
    @Test fun feedIsNewestFirstAndBounded() {
        repeat(40) { RemoteActivity.record(RemoteActivity.ONEPLUS, "Change $it " + "x".repeat(200)) }
        val entries = RemoteActivity.entries.value
        assertEquals(30, entries.size)
        assertTrue(entries.first().text.startsWith("Change 39"))
        assertTrue(entries.all { it.text.length <= 120 })
        assertTrue(entries.zipWithNext().all { (newer, older) -> newer.id > older.id })
    }

    @Test fun feedCrossesTheWireAsAgeNotClockTime() {
        val samsungNow = 1_000_000_000L
        val sent = listOf(
            ActivityEntry(2, samsungNow - 5_000, RemoteActivity.SAMSUNG, "Pause"),
            ActivityEntry(1, samsungNow - 60_000, RemoteActivity.ONEPLUS, "Volume 40%"),
        )
        val wire = JSONArray(RemoteActivity.toJson(sent, samsungNow).toString())
        val oneplusNow = 5_000_000_000L // A skewed clock on the other phone.
        val received = RemoteActivity.fromJson(wire, oneplusNow)
        assertEquals(listOf("Pause", "Volume 40%"), received?.map { it.text })
        assertEquals(listOf(RemoteActivity.SAMSUNG, RemoteActivity.ONEPLUS), received?.map { it.source })
        assertEquals(listOf(oneplusNow - 5_000, oneplusNow - 60_000), received?.map { it.at })
    }

    @Test fun peerFeedIsValidatedAndBounded() {
        assertNull(RemoteActivity.fromJson(null, 0))
        val wire = JSONArray()
        wire.put(JSONObject().put("text", " ").put("source", "OnePlus"))
        wire.put(JSONObject().put("text", "y".repeat(500)).put("source", "z".repeat(50)).put("ago", -9))
        repeat(20) { wire.put(JSONObject().put("text", "ok")) }
        val received = RemoteActivity.fromJson(wire, 10_000)
        assertEquals(7, received?.size) // 8 read, the blank one skipped.
        assertEquals(120, received?.first()?.text?.length)
        assertEquals(20, received?.first()?.source?.length)
        assertEquals(10_000L, received?.first()?.at)
    }

    @Test fun controllerSendsWhatItShowedInsteadOfAToggle() {
        val shown = RemoteUiState(playing = true, shuffle = false, trackIndex = 4)
        assertFalse(commandRequest("playPause", shown).getBoolean("play"))
        assertTrue(commandRequest("playPause", shown.copy(playing = false)).getBoolean("play"))
        assertTrue(commandRequest("shuffle", shown).getBoolean("on"))
        assertEquals(4, commandRequest("next", shown).getInt("index"))
        assertEquals(4, commandRequest("previous", shown).getInt("index"))
        assertFalse(commandRequest("next", shown.copy(trackIndex = -1)).has("index"))
        assertEquals(30, commandRequest("volume", shown, percent = 30).getInt("percent"))
        assertFalse(commandRequest("volume", shown, percent = 30).has("play"))
    }

    @Test fun samsungAppliesIntentSoAStaleTapCannotUndoIt() {
        // Samsung already resumed; the OnePlus still showed Paused and taps Play.
        assertTrue(wantsPlaying(requested = true, playing = true))
        assertFalse(wantsPlaying(requested = false, playing = false))
        assertTrue(wantsPlaying(requested = true, playing = false))
        // An older controller sends nothing and keeps the toggle.
        assertFalse(wantsPlaying(requested = null, playing = true))
        assertTrue(wantsPlaying(requested = null, playing = false))
    }

    @Test fun skipOfASongSamsungAlreadyLeftIsRefused() {
        assertTrue(isStaleSkip(requestedIndex = 3, currentIndex = 4))
        assertFalse(isStaleSkip(requestedIndex = 4, currentIndex = 4))
        assertFalse(isStaleSkip(requestedIndex = null, currentIndex = 4))
    }

    @Test fun aRejectedSongKeepsTheLiveCast() {
        assertTrue(endsCast("next", accepted = null))
        assertTrue(endsCast("playPause", accepted = null))
        assertFalse(endsCast("playTrack", accepted = null))
        assertFalse(endsCast("playTrack", accepted = false))
        assertTrue(endsCast("playTrack", accepted = true))
        assertTrue(endsCast("playPlaylist", accepted = true))
        assertFalse(endsCast("saveTrack", accepted = true))
        assertFalse(endsCast("volume", accepted = null))
    }

    @Test fun onlyAKnownRevisionMoveRefreshesTheLibrary() {
        assertFalse(libraryChanged(null, 5))
        assertFalse(libraryChanged(5, null))
        assertFalse(libraryChanged(5, 5))
        assertTrue(libraryChanged(5, 6))
    }

    @Test fun breakChangesReadTheSameOnBothPhones() {
        assertEquals("Changed music breaks (on)", breaksActivityText(MusicBreakSettings(enabled = true)))
        assertEquals("Changed music breaks (off)", breaksActivityText(MusicBreakSettings()))
    }
}
