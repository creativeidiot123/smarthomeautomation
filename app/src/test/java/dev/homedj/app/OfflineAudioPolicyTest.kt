package dev.homedj.app

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class OfflineAudioPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun downloadDiagnosticsDistinguishRefusalsExtractionAndTransferFailuresWithoutLeakingText() {
        assertEquals("ContentNotAvailableException",
            OfflineAudioLibrary.failureDetail(TrackUnavailableException("ContentNotAvailableException")))
        assertEquals("no_audio_stream", OfflineAudioLibrary.failureDetail(
            TrackUnavailableException("No unprotected, directly downloadable audio stream available")))
        assertEquals("ParsingException", OfflineAudioLibrary.failureDetail(IOException("Extraction failed: ParsingException")))
        assertEquals("http_403", OfflineAudioLibrary.failureDetail(IOException("Audio HTTP status 403")))
        assertEquals("incomplete_chunk", OfflineAudioLibrary.failureDetail(IOException("Audio chunk incomplete")))
        assertEquals("missing_content_range", OfflineAudioLibrary.failureDetail(IOException("Missing Content-Range")))
        listOf("https://cdn.googlevideo.com/audio?token=secret", "Server body with account@example.com",
            "Audio HTTP status 403 at https://example.com/secret", "Extraction failed: secret with spaces").forEach {
            assertEquals("unspecified", OfflineAudioLibrary.failureDetail(IOException(it)))
        }
    }

    @Test fun aSingleDirectoryScanFindsOnlyCommittedAudioFiles() {
        val folder = temporary.newFolder()
        listOf("aaaaaaaaaaa.m4a", "bbbbbbbbbbb.opus", "bad.m4a", "ccccccccccc.mp3", "ddddddddddd.part",
            "eeeeeeeeeee.auto.part").forEach { java.io.File(folder, it).writeBytes(byteArrayOf(1)) }
        java.io.File(folder, "fffffffffff.webm").createNewFile()
        java.io.File(folder, "ggggggggggg.m4a").mkdir()
        assertEquals(setOf("aaaaaaaaaaa.m4a", "bbbbbbbbbbb.opus"),
            OfflineAudioLibrary.savedFiles(folder).mapTo(hashSetOf()) { it.name })
    }

    @Test fun largeSongsAndLibrariesHaveNoAppSizeQuota() = runBlocking {
        val actual = temporary.newFolder()
        val saved = File(actual, "aaaaaaaaaaa.m4a").apply { writeBytes(byteArrayOf(9)) }
        // Model a large existing library without allocating gigabytes in a unit test.
        val libraryEntry = object : File(saved.path) { override fun length() = 3L * 1024 * 1024 * 1024 }
        val folder = object : File(actual.path) { override fun listFiles() = arrayOf<File>(libraryEntry) }
        val bytes = byteArrayOf(1, 2, 3)
        val file = OfflineAudioLibrary.transfer(folder, "bbbbbbbbbbb", automatic = false, authorized = { true },
            resolve = { listOf(NativeAudioSource.Link("https://test.googlevideo.com/audio", "m4a", 140)) },
            fetch = { _, partial, checkpoint ->
                checkpoint(256L * 1024 * 1024)
                partial.writeBytes(bytes)
                bytes.size.toLong()
            })
        assertEquals(File(actual, "bbbbbbbbbbb.m4a"), file)
        assertArrayEquals(bytes, file.readBytes())
        assertArrayEquals(byteArrayOf(9), saved.readBytes())
        assertFalse(OfflineAudioLibrary.partialFile(actual, "bbbbbbbbbbb", false, 140).exists())
    }

    @Test fun aSavedSongReturnsWithoutResolutionOrAnotherTransfer() = runBlocking {
        val folder = temporary.newFolder()
        val saved = File(folder, "aaaaaaaaaaa.m4a").apply { writeBytes(byteArrayOf(9)) }
        val result = OfflineAudioLibrary.transfer(folder, "aaaaaaaaaaa", false,
            authorized = { throw AssertionError("Saved song waited for authorization") },
            resolve = { throw AssertionError("Saved song was resolved again") },
            fetch = { _, _, _ -> throw AssertionError("Saved song was downloaded again") })
        assertEquals(saved, result)
        assertArrayEquals(byteArrayOf(9), saved.readBytes())
    }

    @Test fun exhaustedDeviceStorageStillStopsWithoutDamagingSavedSongs() = runBlocking {
        val actual = temporary.newFolder()
        val saved = File(actual, "aaaaaaaaaaa.m4a").apply { writeBytes(byteArrayOf(9)) }
        val folder = object : File(actual.path) { override fun getUsableSpace() = 0L }
        try {
            OfflineAudioLibrary.transfer(folder, "bbbbbbbbbbb", false, authorized = { true },
                resolve = { listOf(NativeAudioSource.Link("https://test.googlevideo.com/audio", "m4a", 140)) },
                fetch = { _, partial, checkpoint ->
                    partial.writeBytes(byteArrayOf(1))
                    checkpoint(-1)
                    throw AssertionError("Download continued with no device storage")
                })
            throw AssertionError("Download ignored exhausted device storage")
        } catch (rejected: DownloadRejected) {
            assertEquals("Device storage nearly full", rejected.message)
        }
        assertArrayEquals(byteArrayOf(9), saved.readBytes())
        assertFalse(File(actual, "bbbbbbbbbbb.m4a").exists())
        assertFalse(OfflineAudioLibrary.partialFile(actual, "bbbbbbbbbbb", false, 140).exists())
    }

    @Test fun backgroundPlaylistTransfersRequireUnmeteredConnection() {
        assertEquals(NetworkType.UNMETERED,
            OfflineAudioLibrary.networkForDownload("account", "playlist"))
        // Songs a refresh found in a downloaded playlist also wait for Wi-Fi.
        assertEquals(NetworkType.UNMETERED,
            OfflineAudioLibrary.networkForDownload(null, "playlist"))
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

    @Test fun downloadingTheRestReportsOnlySongsActuallyQueued() {
        assertEquals("All these songs are already downloaded.", queuedNotice(0, "for offline saving"))
        assertEquals("Queued 1 song for offline saving.", queuedNotice(1, "for offline saving"))
        assertEquals("Queued 4 songs from the linked playlist.", queuedNotice(4, "from the linked playlist"))
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

    @Test fun fourSongsCanTransferTogetherAndAFifthWaits() = runBlocking {
        assertFourTransfers()
    }

    private suspend fun assertFourTransfers() = coroutineScope {
        val release = CompletableDeferred<Unit>()
        val entered = List(5) { CompletableDeferred<Unit>() }
        val jobs = entered.map { started ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                OfflineAudioLibrary.limitedTransfer {
                    started.complete(Unit)
                    release.await()
                }
            }
        }
        try {
            assertEquals(listOf(true, true, true, true, false), entered.map { it.isCompleted })
            release.complete(Unit)
            withTimeout(5_000) { jobs.forEach { it.join() } }
            assertTrue(entered.all { it.isCompleted })
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    @Test fun errorsAndCancellationReleaseSlotsForTheNextSongs() = runBlocking {
        listOf(IOException("Download failed"), CancellationException("Stopped")).forEach { failure ->
            val caught = try {
                OfflineAudioLibrary.limitedTransfer { throw failure }
            } catch (exception: Exception) {
                exception
            }
            assertSame(failure, caught)
        }
        // A leaked permit would leave one of these four songs waiting.
        assertFourTransfers()
    }
}
