package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hourly and manual playlist refresh: which songs are new, which download, and what both phones say. */
class PlaylistRefreshTest {
    private fun song(id: String) = YouTubeTrack(id, "Song $id")
    private val before = listOf(song("aaaaaaaaaaa"), song("bbbbbbbbbbb"))
    private val after = listOf(song("bbbbbbbbbbb"), song("ccccccccccc"), song("ddddddddddd"))

    @Test fun onlySongsTheLastReadDidNotListAreNew() {
        assertEquals(listOf("ccccccccccc", "ddddddddddd"), PlaylistRefresh.newSongs(before, after).map { it.id })
        // A playlist never read before has no baseline: its whole list is not "new".
        assertTrue(PlaylistRefresh.newSongs(emptyList(), after).isEmpty())
    }

    @Test fun newSongsDownloadOnlyForAPlaylistWithADownloadedOrDownloadingSong() {
        assertTrue(PlaylistRefresh.additions(before, after) { false }.isEmpty())
        val kept = PlaylistRefresh.additions(before, after) { it == "aaaaaaaaaaa" }
        assertEquals(listOf("ccccccccccc", "ddddddddddd"), kept.map { it.id })
        // The downloaded song may have left the playlist upstream; the playlist still counts.
        assertEquals(2, PlaylistRefresh.additions(before, after) { it == "aaaaaaaaaaa" }.size)
        assertTrue(PlaylistRefresh.additions(emptyList(), after) { true }.isEmpty())
    }

    @Test fun aRefreshReplacesSongsAndLinkDetailsInPlaceKeepingOrder() {
        val first = YouTubePlaylist("PLfirst", "First", 2)
        val linked = YouTubePlaylist("PLlinked", "Old name", 2)
        val catalog = SavedPlaylistCatalog(owned = listOf(first), imported = listOf(linked),
            tracks = mapOf("PLlinked" to before, "PLfirst" to before))
        val renamed = YouTubePlaylist("PLlinked", "New name", 3, "https://i.ytimg.com/cover.jpg")
        val next = catalog.withRefreshed("PLlinked", after, renamed)
        assertEquals(listOf(renamed, first), next.playlists)
        assertEquals(after, next.tracks["PLlinked"])
        assertEquals(before, next.tracks["PLfirst"])
        // An API read carries no details: the playlist entry stays.
        assertEquals(listOf(linked, first), catalog.withRefreshed("PLlinked", after, null).playlists)
    }

    @Test fun messagesSayWhatChangedOnBothPhones() {
        assertEquals("Mix is up to date.", refreshMessage(RefreshOutcome.Done("Mix", false, 0, 0)))
        assertEquals("Refreshed Mix.", refreshMessage(RefreshOutcome.Done("Mix", true, 0, 0)))
        assertEquals("Found 1 new song in Mix.", refreshMessage(RefreshOutcome.Done("Mix", true, 1, 0)))
        assertEquals("Found 3 new songs in Mix. Downloading 2 on Wi-Fi.",
            refreshMessage(RefreshOutcome.Done("Mix", true, 3, 2)))
        assertEquals("This playlist is private or no longer on YouTube.", refreshError(RefreshOutcome.Unavailable))
        assertEquals("Unknown playlist", refreshError(RefreshOutcome.Unknown))
    }

    @Test fun anOlderSamsungWithoutRefreshAsksForAnUpdate() {
        assertEquals("Update HomeDJ on Samsung to refresh playlists.", refreshPlaylistError("Unknown command"))
        assertEquals("Samsung could not refresh this playlist.", refreshPlaylistError(""))
        assertEquals("Unknown playlist", refreshPlaylistError("Unknown playlist"))
    }
}
