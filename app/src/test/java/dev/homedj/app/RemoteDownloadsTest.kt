package dev.homedj.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Seeking and download management over the LAN, including peers on older builds. */
class RemoteDownloadsTest {
    private val songs = listOf(
        RemoteSong("aaaaaaaaaaa", "One", saved = true),
        RemoteSong("bbbbbbbbbbb", "Two", saved = false, saving = true),
        RemoteSong("ccccccccccc", "Three", saved = false),
    )
    private val playlist = RemoteUiState(browserMode = "tracks", selectedPlaylistId = "PL1", songs = songs,
        browserTotal = 3, savedCount = 1, savingCount = 1)

    @Test fun seekIsRefusedForASongSamsungAlreadyLeftAndClampedToTheEnd() {
        assertNull(seekTarget(requestedIndex = 2, currentIndex = 3, positionMs = 1_000, durationMs = 5_000))
        assertNull(seekTarget(requestedIndex = 3, currentIndex = 3, positionMs = -1, durationMs = 5_000))
        assertEquals(5_000L, seekTarget(requestedIndex = 3, currentIndex = 3, positionMs = 9_000, durationMs = 5_000))
        assertEquals(1_000L, seekTarget(requestedIndex = null, currentIndex = 3, positionMs = 1_000, durationMs = -1))
    }

    @Test fun seekSendsTheShownSongAndPosition() {
        val request = commandRequest("seek", RemoteUiState(trackIndex = 4), position = 61_000)
        assertEquals(4, request.getInt("index"))
        assertEquals(61_000L, request.getLong("position"))
    }

    @Test fun songRowsReadDetailsWhenSentAndDefaultsFromOlderSamsungs() {
        val newer = remoteSong(JSONObject().put("id", "aaaaaaaaaaa").put("title", "One").put("saved", true)
            .put("artist", "Band").put("duration", 185))
        assertEquals(RemoteSong("aaaaaaaaaaa", "One", true, false, "Band", 185), newer)
        val older = remoteSong(JSONObject().put("id", "aaaaaaaaaaa").put("title", "One"))
        assertEquals(RemoteSong("aaaaaaaaaaa", "One", false), older)
        assertEquals(0, remoteSong(JSONObject().put("duration", -5)).durationSeconds)
    }

    @Test fun cancellingAPlaylistStopsEverySavingSong() {
        val next = playlist.withSavesQueued("cancelSave", "PL1", "playlist")
        assertFalse(next.songs.any { it.saving })
        assertEquals(0, next.savingCount)
        assertEquals(1, next.savedCount)
    }

    @Test fun removingAPlaylistLeavesNothingSaved() {
        val next = playlist.withSavesQueued("removeSaved", "PL1", "playlist")
        assertFalse(next.songs.any { it.saved || it.saving })
        assertEquals(0, next.savedCount)
    }

    @Test fun removingOneSongTouchesOnlyThatSong() {
        val next = playlist.withSavesQueued("removeSaved", "aaaaaaaaaaa", "track")
        assertEquals(listOf(false, false, false), next.songs.map { it.saved })
        assertEquals(true, next.songs[1].saving)
        assertEquals(0, next.savedCount)
    }

    @Test fun removingFromTheDownloadsListDropsTheRow() {
        val downloads = RemoteUiState(browserMode = "downloads", songs = songs.take(1), browserTotal = 1)
        val next = downloads.withSavesQueued("removeSaved", "aaaaaaaaaaa", "track")
        assertEquals(emptyList<RemoteSong>(), next.songs)
        assertEquals(0, next.browserTotal)
    }

    @Test fun savingOneSongCountsItOnce() {
        val once = playlist.withSavesQueued("saveTrack", "ccccccccccc")
        assertEquals(2, once.savingCount)
        assertEquals(2, once.withSavesQueued("saveTrack", "ccccccccccc").savingCount)
        assertEquals(2, playlist.withSavesQueued("savePlaylist", "PL1").savingCount)
    }

    @Test fun anOlderSamsungGetsAnUpdateHint() {
        assertEquals("Update HomeDJ on Samsung to manage downloads.", downloadChangeError("removeSaved", "Unknown command"))
        assertEquals("Unknown playlist", downloadChangeError("cancelSave", "Unknown playlist"))
        assertEquals("Unknown command", downloadChangeError("playTrack", "Unknown command"))
    }
}
