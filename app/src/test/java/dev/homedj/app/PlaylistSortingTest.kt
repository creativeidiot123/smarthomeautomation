package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSortingTest {
    private fun song(id: String, title: String, artist: String = "", seconds: Int = 0, saved: Boolean = false) =
        PageSong(id, title, songDetails(artist, seconds), null, saved, saving = false,
            artist = artist, durationSeconds = seconds)

    private val songs = listOf(
        song("b", "beta", "Zulu", 600),
        song("a", "Alpha", "alpha", 90, saved = true),
        song("c", "charlie", "Mike", 180),
    )

    @Test fun everySortHasTheExpectedVisibleOrder() {
        val expected = mapOf(
            PlaylistSort.ORIGINAL to listOf("b", "a", "c"),
            PlaylistSort.REVERSE to listOf("c", "a", "b"),
            PlaylistSort.TITLE_ASC to listOf("a", "b", "c"),
            PlaylistSort.TITLE_DESC to listOf("c", "b", "a"),
            PlaylistSort.ARTIST_ASC to listOf("a", "c", "b"),
            PlaylistSort.ARTIST_DESC to listOf("b", "c", "a"),
            PlaylistSort.SHORTEST to listOf("a", "c", "b"),
            PlaylistSort.LONGEST to listOf("b", "c", "a"),
            PlaylistSort.DOWNLOADED to listOf("a", "b", "c"),
        )
        expected.forEach { (sort, ids) ->
            assertEquals(sort.label, ids, sortedPlaylistSongs(songs, sort).map { it.id })
        }
    }

    @Test fun unavailableMetadataStaysAtTheEndInBothDirections() {
        val input = listOf(song("unknown", "Unknown"), songs[0], song("blank", "Blank", "  "), songs[1])
        listOf(PlaylistSort.ARTIST_ASC, PlaylistSort.SHORTEST).forEach { sort ->
            assertEquals(listOf("a", "b", "unknown", "blank"), sortedPlaylistSongs(input, sort).map { it.id })
        }
        listOf(PlaylistSort.ARTIST_DESC, PlaylistSort.LONGEST).forEach { sort ->
            assertEquals(listOf("b", "a", "unknown", "blank"), sortedPlaylistSongs(input, sort).map { it.id })
        }
    }

    @Test fun equalValuesKeepPlaylistOrderAndIgnoreCaseAndSurroundingSpaces() {
        val input = listOf(song("first", " SAME ", " Band ", 90), song("second", "same", "band", 90))
        PlaylistSort.entries.filter { it != PlaylistSort.REVERSE }.forEach { sort ->
            assertEquals(sort.label, input, sortedPlaylistSongs(input, sort))
        }
    }

    @Test fun sortingDoesNotChangeTheSourceOrSongActionIdentity() {
        val source = songs.toMutableList()
        PlaylistSort.entries.forEach { sort ->
            val result = sortedPlaylistSongs(source, sort)
            assertEquals(listOf("b", "a", "c"), source.map { it.id })
            result.forEach { row -> assertSame(source.first { it.id == row.id }, row) }
        }
        assertEquals(songs, sortedPlaylistSongs(source, PlaylistSort.ORIGINAL))
    }

    @Test fun searchAndSortIncludeSongsBeyondTheVisibleRowLimit() {
        val input = (0..120).map { song("$it", "Song $it", "Artist", seconds = it + 1) }
        assertEquals("120", sortedPlaylistSongs(input, PlaylistSort.LONGEST).first().id)
        assertEquals(listOf("120", "12"),
            sortedPlaylistSongs(input, PlaylistSort.LONGEST, "Song 12").map { it.id })
        assertEquals(input.size, sortedPlaylistSongs(input, PlaylistSort.ORIGINAL, "ARTIST").size)
        assertTrue(sortedPlaylistSongs(input, PlaylistSort.TITLE_ASC, "missing").isEmpty())
    }

    @Test fun addedPagesAndDownloadChangesUseTheCurrentSort() {
        val firstPage = songs.take(1)
        assertEquals(listOf("b"), sortedPlaylistSongs(firstPage, PlaylistSort.TITLE_ASC).map { it.id })
        assertEquals(listOf("a", "b", "c"), sortedPlaylistSongs(songs, PlaylistSort.TITLE_ASC).map { it.id })
        val changed = listOf(song("b", "beta", saved = true), song("a", "Alpha"))
        assertEquals(listOf("b", "a"), sortedPlaylistSongs(changed, PlaylistSort.DOWNLOADED).map { it.id })
    }

    @Test fun emptyAndSingleSongPlaylistsAreSafeForEverySort() {
        PlaylistSort.entries.forEach { sort ->
            assertTrue(sortedPlaylistSongs(emptyList(), sort).isEmpty())
            assertEquals(songs.take(1), sortedPlaylistSongs(songs.take(1), sort))
        }
    }
}
