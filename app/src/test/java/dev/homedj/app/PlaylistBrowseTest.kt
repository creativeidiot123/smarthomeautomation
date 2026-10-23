package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistBrowseTest {
    private val songs = List(260) { YouTubeTrack("song$it", "Song $it") }
    private val saved = SavedPlaylistCatalog(tracks = mapOf("playlist" to songs, "empty" to emptyList()),
        ownerKey = SavedPlaylistStore.accountKey("test@example.invalid"))

    @Test fun openingAnAlreadySyncedPlaylistUsesTheWholeStoredList() {
        assertEquals(songs, cachedPlaylistTracks(saved, "playlist", "test@example.invalid"))
        assertEquals(songs, cachedPlaylistTracks(saved, "playlist", null))
    }

    @Test fun anEmptySyncedPlaylistIsACacheHitButAnUnloadedPlaylistIsNot() {
        assertEquals(emptyList<YouTubeTrack>(), cachedPlaylistTracks(saved, "empty", null))
        assertNull(cachedPlaylistTracks(saved, "unloaded", null))
    }

    @Test fun accountSwitchCannotReusePreviousAccountsSongs() {
        assertNull(cachedPlaylistTracks(saved, "playlist", "other@example.invalid"))
    }

    @Test fun scrollingRowsIncludeEverySongAndRepeatedVideosHaveDistinctKeys() {
        val rows = songs.map { PageSong(it.id, it.title, "", null, false, false) }
        val full = rows + rows.first()
        val keyed = keyedPlaylistSongs(full)
        assertEquals(full, keyed.map { it.second })
        assertEquals(261, keyed.map { it.first }.distinct().size)
        val before = keyedPlaylistSongs(rows).associate { it.second.id to it.first }
        val after = keyedPlaylistSongs(sortedPlaylistSongs(rows, PlaylistSort.TITLE_DESC))
            .associate { it.second.id to it.first }
        assertEquals(before, after)
    }
}
