package dev.homedj.app

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBrowseCacheTest {
    private val songs = List(260) { RemoteSong("song$it", "Song $it", saved = it % 2 == 0,
        artist = "Artist $it", durationSeconds = it + 1) }

    private fun reply(offset: Int, total: Int = songs.size): JSONObject = JSONObject()
        .put("ok", true).put("total", total).put("items", JSONArray().also { values ->
            songs.drop(offset).take(40).forEach {
                values.put(SavedPlaylistCodec.trackJson(YouTubeTrack(it.id, it.title, it.artist, it.durationSeconds))
                    .put("saved", it.saved))
            }
        })

    @Test fun allPagesLoadWithoutAnyMoreUserTapsAndPreservePlaylistOrder() = runBlocking {
        val offsets = mutableListOf<Int>()
        val page = loadRemoteBrowse("tracks") { offset -> offsets.add(offset); reply(offset) }
        assertEquals(listOf(0, 40, 80, 120, 160, 200, 240), offsets)
        assertEquals(songs, page.songs)
        assertEquals(260, page.total)
    }

    @Test fun cachedMetadataSurvivesSerializationInFullWithoutInventingLiveDownloadState() {
        val page = RemoteBrowsePage(songs = songs, total = songs.size, savedCount = 130, savingCount = 3)
        val decoded = RemoteBrowseCodec.decode(JSONObject(RemoteBrowseCodec.encode(page).toString()))
        assertEquals(songs.map { it.copy(saved = false) }, decoded.songs)
        assertEquals(-1, decoded.savedCount)
        assertEquals(0, decoded.savingCount)
        assertEquals(songs.size, decoded.total)
        assertFalse(RemoteBrowseCodec.encode(page).toString().contains("saved"))
    }

    @Test fun cachedCatalogPreservesOrderAndValidatedArt() {
        val playlists = List(130) { RemotePlaylist("playlist$it", "Playlist $it", it,
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg") }
        val page = RemoteBrowsePage(playlists = playlists, total = playlists.size)
        assertEquals(page, RemoteBrowseCodec.decode(JSONObject(RemoteBrowseCodec.encode(page).toString())))
    }

    @Test fun pairingOrLibraryRevisionChangesInvalidateTheCache() {
        val peer = browsePeerKey("192.168.1.2", "test-pairing")
        assertTrue(browseCacheMatches(peer, 15, peer, 15))
        assertTrue(browseCacheMatches(peer, 15, peer, null))
        assertFalse(browseCacheMatches(peer, 15, peer, 16))
        assertFalse(browseCacheMatches(peer, 15, browsePeerKey("192.168.1.2", "other-pairing"), 15))
        assertFalse(browseCacheMatches(peer, 15, browsePeerKey("192.168.1.3", "test-pairing"), 15))
        assertFalse(peer.contains("test-pairing"))
        assertFalse(peer.contains("192.168.1.2"))
    }

    @Test fun missingEmptyOrOversizedPagesTerminateInsteadOfLooping() = runBlocking {
        val broken = listOf(
            JSONObject().put("ok", false),
            JSONObject().put("ok", true).put("total", 5),
            JSONObject().put("ok", true).put("total", 5).put("items", JSONArray()),
            reply(0, 5_001), reply(0, 2), reply(0, -1),
        )
        for (response in broken) {
            var requests = 0
            try {
                loadRemoteBrowse("tracks") { requests++; response }
                throw AssertionError("Malformed page accepted")
            } catch (_: IOException) {
                assertEquals(1, requests)
            }
        }
    }

    @Test fun aChangingListCannotBeCommittedAsACompleteSnapshot() = runBlocking {
        try {
            loadRemoteBrowse("tracks") { offset -> reply(offset, if (offset == 0) 260 else 259) }
            throw AssertionError("Mixed snapshots accepted")
        } catch (_: IOException) { }
    }

    @Test(timeout = 5_000) fun navigationCancellationStopsPagingAndPreventsAnObsoleteCompletion() = runBlocking {
        val secondPage = CompletableDeferred<Unit>()
        val waitForNavigation = CompletableDeferred<Unit>()
        var published: RemoteBrowsePage? = null
        val job = launch {
            published = loadRemoteBrowse("tracks") { offset ->
                if (offset > 0) { secondPage.complete(Unit); waitForNavigation.await() }
                reply(offset)
            }
        }
        secondPage.await()
        job.cancelAndJoin()
        waitForNavigation.complete(Unit)
        assertEquals(null, published)
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteMetadataIsNeverRestoredAsACompletePlaylist() {
        RemoteBrowseCodec.decode(RemoteBrowseCodec.encode(RemoteBrowsePage(songs = songs.take(40), total = 260)))
    }

    @Test fun revisitingUnchangedListsNeedsNoMetadataTransfer() {
        val page = RemoteBrowsePage(songs = songs, total = songs.size, downloadsRevision = 42)
        assertTrue(page.reusable("tracks", 42))
        assertTrue(page.reusable("downloads", 42))
        assertTrue(page.reusable("catalog", null))
        assertFalse(page.reusable("tracks", 43))
        assertFalse(page.reusable("tracks", null))
        assertFalse(page.copy(songs = songs + RemoteSong("saving", "Saving", false, saving = true)).reusable("tracks", 42))
        // Disk restoration deliberately has no live flags/revision and must re-read them once.
        assertFalse(RemoteBrowseCodec.decode(RemoteBrowseCodec.encode(page)).reusable("tracks", 42))
    }
}
