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
            OfflineAudioLibrary.offlineTitle("dQw4w9WgXcQ", catalog.titles()))
        assertEquals("kJQP7kiw5Fk", OfflineAudioLibrary.offlineTitle("kJQP7kiw5Fk", catalog.titles()))
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
