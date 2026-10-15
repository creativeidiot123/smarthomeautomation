package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteReconnectPolicyTest {
    @Test fun healthyPollingIsLessFrequent() {
        assertEquals(6_000L, statusRetryDelayMillis(0))
        assertEquals(6_000L, statusRetryDelayMillis(-1))
    }

    @Test fun failuresBackOffAndCapWithoutOverflow() {
        assertEquals(1_000L, statusRetryDelayMillis(1))
        assertEquals(2_000L, statusRetryDelayMillis(2))
        assertEquals(4_000L, statusRetryDelayMillis(3))
        assertEquals(8_000L, statusRetryDelayMillis(4))
        assertEquals(16_000L, statusRetryDelayMillis(5))
        assertEquals(30_000L, statusRetryDelayMillis(6))
        assertEquals(30_000L, statusRetryDelayMillis(Int.MAX_VALUE))
    }

    @Test fun singleMissedPollKeepsLiveLink() {
        assertFalse(showsDisconnected(consecutiveFailures = 1, connected = true))
        assertTrue(showsDisconnected(consecutiveFailures = 2, connected = true))
        assertTrue(showsDisconnected(consecutiveFailures = 1, connected = false))
    }

    @Test fun reconnectKeepsLoadedLibrary() {
        assertTrue(RemoteUiState().needsBrowse())
        assertFalse(RemoteUiState(playlists = listOf(RemotePlaylist("PL1", "Mix", 3))).needsBrowse())
        val tracks = RemoteUiState(browserMode = "tracks", selectedPlaylistId = "PL1")
        assertTrue(tracks.needsBrowse())
        assertFalse(tracks.copy(songs = listOf(RemoteSong("dQw4w9WgXcQ", "Song", false))).needsBrowse())
    }
}
