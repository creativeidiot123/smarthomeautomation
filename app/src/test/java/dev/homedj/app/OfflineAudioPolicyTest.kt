package dev.homedj.app

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAudioPolicyTest {
    @Test fun backgroundPlaylistTransfersRequireUnmeteredConnection() {
        assertEquals(NetworkType.UNMETERED,
            OfflineAudioLibrary.networkForDownload("account", "playlist"))
        assertEquals(NetworkType.CONNECTED,
            OfflineAudioLibrary.networkForDownload(null, null))
        assertEquals(NetworkType.CONNECTED,
            OfflineAudioLibrary.networkForDownload("account", null))
    }

    @Test fun manualSaveIsNotSwallowedByPendingAutomaticJob() {
        assertNotEquals(OfflineAudioLibrary.workName("dQw4w9WgXcQ", automatic = true),
            OfflineAudioLibrary.workName("dQw4w9WgXcQ", automatic = false))
    }

    @Test fun offlineSongsAreNamedFromTheSavedCatalog() {
        val catalog = SavedPlaylistCatalog(
            imported = listOf(YouTubePlaylist("PL1", "Mix", 2)),
            tracks = mapOf("PL1" to listOf(YouTubeTrack("dQw4w9WgXcQ", "Never Gonna Give You Up"))),
        )
        assertEquals("Never Gonna Give You Up",
            OfflineAudioLibrary.offlineTrack("dQw4w9WgXcQ", null, catalog.songs()).title)
        assertEquals("kJQP7kiw5Fk", OfflineAudioLibrary.offlineTrack("kJQP7kiw5Fk", null, catalog.songs()).title)
    }

    @Test fun detailsSavedWithTheDownloadWinOverTheCatalog() {
        val saved = YouTubeTrack("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", 213)
        val catalog = mapOf("dQw4w9WgXcQ" to YouTubeTrack("dQw4w9WgXcQ", "Old title"))
        assertEquals(saved, OfflineAudioLibrary.offlineTrack("dQw4w9WgXcQ", saved, catalog))
    }

    @Test fun playlistDownloadStateFollowsSavedAndPendingSongs() {
        assertEquals(DownloadState.NONE, downloadState(total = 0, saved = 0, saving = 0))
        assertEquals(DownloadState.NONE, downloadState(total = 5, saved = 0, saving = 0))
        assertEquals(DownloadState.PARTIAL, downloadState(total = 5, saved = 2, saving = 0))
        assertEquals(DownloadState.DOWNLOADING, downloadState(total = 5, saved = 4, saving = 1))
        assertEquals(DownloadState.DONE, downloadState(total = 5, saved = 5, saving = 0))
    }

    @Test fun removalMessagesCountSongsGrammatically() {
        assertEquals("1 downloaded song", songCount(1))
        assertEquals("0 downloaded songs", songCount(0))
        assertEquals("3 downloaded songs", songCount(3))
    }

    @Test fun aLargePlaylistSaveRunsOnlyAFewTransfersAtOnce() = runBlocking {
        // WorkManager starts every queued save at once; unbounded they starved the shared IO pool.
        val running = AtomicInteger()
        val peak = AtomicInteger()
        List(20) {
            launch(Dispatchers.Default) {
                OfflineAudioLibrary.limitedTransfer {
                    peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    delay(20)
                    running.decrementAndGet()
                }
            }
        }.forEach { it.join() }
        assertTrue(peak.get() in 1..OfflineAudioLibrary.MAX_PARALLEL_TRANSFERS)
    }
}
