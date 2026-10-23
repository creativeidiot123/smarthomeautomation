package dev.homedj.app

import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistQueueTest {
    private fun id(n: Int) = "song" + n.toString().padStart(7, '0')
    private val playlist = (0 until 10).map { YouTubeTrack(id(it), "Song $it") }

    @Test fun tappedSongQueuesItsWholePlaylistSoNextAndPreviousWork() {
        val queue = playlistQueue(playlist, id(4))!!
        assertEquals(playlist, queue.tracks)
        assertEquals(4, queue.start)
    }

    @Test fun aPlaylistOfMoreThan500SongsQueuesEverySong() {
        // A 700-song playlist used to stop at 500, so its last songs never played.
        val long = (0 until 700).map { YouTubeTrack(id(it), "Song $it") }
        val queue = playlistQueue(long, id(650))
        assertEquals(long, queue?.tracks)
        assertEquals(650, queue?.start)
        assertEquals(2_000, MAX_PLAYLIST_SONGS)
    }

    @Test fun songOutsideThePlaylistQueuesNothing() {
        assertNull(playlistQueue(playlist, id(42)))
        assertNull(playlistQueue(playlist, "../bad"))
    }

    @Test fun invalidAndDuplicateSongsAreDroppedWithoutLosingTheStart() {
        val messy = listOf(YouTubeTrack("bad id", "x"), playlist[0], playlist[0], playlist[1])
        val queue = playlistQueue(messy, id(1))!!
        assertEquals(listOf(playlist[0], playlist[1]), queue.tracks)
        assertEquals(1, queue.start)
    }

    @Test fun cappedQueueAlwaysIncludesTheTappedSong() {
        val queue = playlistQueue(playlist, id(8), limit = 3)!!
        assertEquals(3, queue.tracks.size)
        assertEquals(id(8), queue.tracks[queue.start].id)
        assertEquals(id(2), playlistQueue(playlist, id(2), limit = 3)!!.let { it.tracks[it.start].id })
    }

    @Test fun shuffledQueueStartsAtTheTappedSongAndKeepsEverySong() {
        val order = shuffleOrderStartingAt(10, 6, Random(1))
        assertEquals(6, order.first())
        assertEquals((0 until 10).toList(), order.sorted())
    }

    @Test fun pendingSavesAreReadFromWorkTags() {
        val ids = OfflineAudioLibrary.idsFromTags(listOf(
            setOf("homedj_offline", "homedj_offline_id_" + id(1), "dev.homedj.app.OfflineAudioWorker"),
            setOf("homedj_offline", "homedj_offline_id_" + id(2)),
        ))
        assertEquals(setOf(id(1), id(2)), ids)
    }

    @Test fun acceptedSavesShowAsSavingOnTheController() {
        val state = RemoteUiState(browserMode = "tracks", selectedPlaylistId = "PL1", songs = listOf(
            RemoteSong(id(1), "a", saved = true), RemoteSong(id(2), "b", saved = false),
            RemoteSong(id(3), "c", saved = false),
        ))
        val one = state.withSavesQueued("saveTrack", id(2)).songs
        assertTrue(one[1].saving)
        assertFalse(one[2].saving)
        val all = state.withSavesQueued("savePlaylist", "PL1").songs
        assertEquals(listOf(false, true, true), all.map { it.saving })
        assertEquals(state, state.withSavesQueued("savePlaylist", "PL_other"))
        assertEquals(state, state.withSavesQueued("playTrack", id(2)))
    }

    @Test fun olderSamsungReplyWithoutSavingFieldStillParses() {
        val song = remoteSong(JSONObject().put("id", id(1)).put("title", "a").put("saved", false))
        assertFalse(song.saving)
        assertTrue(remoteSong(JSONObject().put("id", id(1)).put("saving", true)).saving)
    }
}
