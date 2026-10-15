package dev.homedj.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLibraryCommandsTest {
    private val known = "dQw4w9WgXcQ"
    private val other = "abcdefghijk"
    private val catalog = SavedPlaylistCatalog(
        tracks = mapOf("playlist" to listOf(YouTubeTrack(known, "Known song")))
    )

    @Test fun remoteCanOnlyQueueKnownOrDownloadedTracks() {
        assertTrue(RemoteLibraryCommands.isTrackAllowed(catalog, known, false))
        assertFalse(RemoteLibraryCommands.isTrackAllowed(catalog, other, false))
        assertTrue(RemoteLibraryCommands.isTrackAllowed(catalog, other, true))
        assertFalse(RemoteLibraryCommands.isTrackAllowed(catalog, "../invalid", true))
    }

    @Test fun rejectsInvalidPages() {
        assertFalse(RemoteLibraryCommands.validOffset(-1))
        assertTrue(RemoteLibraryCommands.validOffset(0))
        assertTrue(RemoteLibraryCommands.validOffset(5_000))
        assertFalse(RemoteLibraryCommands.validOffset(5_001))
    }

    @Test fun songsFromAnAddedLinkBecomePlayableFromTheOnePlus() {
        val linked = LinkedPlaylist(YouTubePlaylist("PL_Link", "Party", 1), listOf(YouTubeTrack(other, "Other")))
        assertTrue(RemoteLibraryCommands.isTrackAllowed(catalog.withPublic(linked), other, false))
    }
}
