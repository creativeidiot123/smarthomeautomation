package dev.homedj.app

import androidx.work.NetworkType
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadBackupTest {
    @get:Rule val temporary = TemporaryFolder()

    private val id = "bbbbbbbbbbb"
    private fun link(name: String, itag: Int = 140) =
        NativeAudioSource.Link("https://test.googlevideo.com/$name", "m4a", itag)

    @Test fun brokenChunksResumeThenTheLinkIsRefreshedThenTheNextStreamIsTried() {
        val drop = IOException("Audio chunk incomplete")
        (0 until DownloadBackup.MAX_RESUMES).forEach {
            assertEquals(DownloadBackup.Step.RESUME, DownloadBackup.step(drop, it, refreshed = false, hasNextStream = true))
        }
        assertEquals(DownloadBackup.Step.REFRESH,
            DownloadBackup.step(drop, DownloadBackup.MAX_RESUMES, refreshed = false, hasNextStream = true))
        assertEquals(DownloadBackup.Step.NEXT_STREAM,
            DownloadBackup.step(drop, DownloadBackup.MAX_RESUMES, refreshed = true, hasNextStream = true))
        assertEquals(DownloadBackup.Step.GIVE_UP,
            DownloadBackup.step(drop, DownloadBackup.MAX_RESUMES, refreshed = true, hasNextStream = false))
    }

    @Test fun aRefusedLinkIsNeverResumedButServerErrorsAre() {
        assertEquals(DownloadBackup.Step.REFRESH, DownloadBackup.step(AudioHttpStatus(403), 0, false, true))
        assertEquals(DownloadBackup.Step.NEXT_STREAM, DownloadBackup.step(AudioHttpStatus(410), 0, true, true))
        assertEquals(DownloadBackup.Step.RESUME, DownloadBackup.step(AudioHttpStatus(503), 0, true, false))
        assertEquals(DownloadBackup.Step.RESUME, DownloadBackup.step(SocketTimeoutException(), 0, true, false))
        // The failure code read by logs is unchanged by the typed exception.
        assertEquals("http_403", OfflineAudioLibrary.failureDetail(AudioHttpStatus(403)))
    }

    @Test fun aDroppedConnectionResumesTheKeptPartialWithoutExtractingAgain() = runBlocking {
        val folder = temporary.newFolder()
        var resolves = 0
        var fetches = 0
        val steps = mutableListOf<DownloadBackup.Step>()
        val file = OfflineAudioLibrary.transfer(folder, id, false, authorized = { true },
            resolve = { resolves++; listOf(link("a")) },
            fetch = { _, partial, _ ->
                partial.appendBytes(byteArrayOf(fetches.toByte()))
                if (++fetches == 1) throw SocketTimeoutException("read timed out")
                partial.length()
            },
            onFallback = { step, _ -> steps += step })
        assertEquals(1, resolves)
        assertEquals(listOf(DownloadBackup.Step.RESUME), steps)
        assertArrayEquals(byteArrayOf(0, 1), file.readBytes())
    }

    @Test fun anExpiredLinkIsExtractedOnceMoreAndTheFreshLinkSaves() = runBlocking {
        val folder = temporary.newFolder()
        val used = mutableListOf<String>()
        var resolves = 0
        val file = OfflineAudioLibrary.transfer(folder, id, false, authorized = { true },
            resolve = { listOf(link(if (resolves++ == 0) "expired" else "fresh")) },
            fetch = { url, partial, _ ->
                used += url.substringAfterLast('/')
                if (url.endsWith("expired")) throw AudioHttpStatus(403)
                partial.writeBytes(byteArrayOf(7))
                1L
            })
        assertEquals(listOf("expired", "fresh"), used)
        assertArrayEquals(byteArrayOf(7), file.readBytes())
    }

    @Test fun aStreamThatStillFailsFallsBackToTheNextFormatAndDropsItsPartial() = runBlocking {
        val folder = temporary.newFolder()
        val used = mutableListOf<String>()
        val file = OfflineAudioLibrary.transfer(folder, id, false, authorized = { true },
            resolve = { listOf(link("opus", 251), link("m4a", 140)) },
            fetch = { url, partial, _ ->
                used += url.substringAfterLast('/')
                if (url.endsWith("opus")) {
                    partial.writeBytes(byteArrayOf(1))
                    throw AudioHttpStatus(403)
                }
                partial.writeBytes(byteArrayOf(2))
                1L
            })
        assertEquals(listOf("opus", "opus", "m4a"), used)
        assertArrayEquals(byteArrayOf(2), file.readBytes())
        assertFalse(OfflineAudioLibrary.partialFile(folder, id, false, 251).exists())
    }

    @Test fun aLinkThatNeverWorksGivesUpAfterABoundedNumberOfTries() = runBlocking {
        val folder = temporary.newFolder()
        var fetches = 0
        var resolves = 0
        try {
            OfflineAudioLibrary.transfer(folder, id, false, authorized = { true },
                resolve = { resolves++; listOf(link("a"), link("b", 251)) },
                fetch = { _, _, _ -> fetches++; throw IOException("Audio chunk incomplete") })
            throw AssertionError("A dead stream reported success")
        } catch (failure: IOException) {
            assertEquals("Audio chunk incomplete", failure.message)
        }
        assertEquals(2, resolves)
        // Each of three links (first, refreshed first, next) gets one try plus its resumes.
        assertEquals(3 * (DownloadBackup.MAX_RESUMES + 1), fetches)
    }

    @Test fun rejectionsAndWithdrawnConsentAreNotRetried() = runBlocking {
        val folder = temporary.newFolder()
        var fetches = 0
        try {
            OfflineAudioLibrary.transfer(folder, id, false, authorized = { true },
                resolve = { listOf(link("a"), link("b", 251)) },
                fetch = { _, _, _ -> fetches++; throw DownloadRejected("Media server returned a non-audio response") })
            throw AssertionError("A rejection reported success")
        } catch (_: DownloadRejected) {
        }
        assertEquals(1, fetches)
        var allowed = true
        try {
            OfflineAudioLibrary.transfer(folder, id, false, authorized = { allowed },
                resolve = { listOf(link("a")) },
                fetch = { _, _, _ -> allowed = false; throw AudioHttpStatus(403) })
            throw AssertionError("A save continued after consent was withdrawn")
        } catch (rejected: DownloadRejected) {
            assertEquals("Download no longer authorized", rejected.message)
        }
    }

    @Test fun downloadsKeepRankedFallbackStreamsBestFirst() {
        val vision = "https://rr1---sn.googlevideo.com/videoplayback?id=1&c=VISIONOS&itag=140"
        val android = "https://rr1---sn.googlevideo.com/videoplayback?id=1&c=ANDROID&itag=251"
        val web = "https://rr1---sn.googlevideo.com/videoplayback?id=1&c=WEB&itag=249"
        assertEquals(listOf(vision, android, web), NativeAudioSource.rankedStreams(
            listOf(web to 64_000, android to 160_000, vision to 128_000, android to 160_000)).map { it.first })
    }

    @Test fun onlyAFirstManualFailureThatTimeMayFixGetsTheLaterTry() {
        val retryLater = listOf(BotCheckException("SignInConfirmNotBotException"), SocketTimeoutException(),
            IOException("Extraction failed: ParsingException"), AudioHttpStatus(403))
        retryLater.forEach { assertTrue(DownloadBackup.wantsLater(it, automatic = false, backup = false)) }
        listOf(TrackUnavailableException("ContentNotAvailableException"), DownloadRejected("Device storage nearly full"))
            .forEach { assertFalse(DownloadBackup.wantsLater(it, automatic = false, backup = false)) }
        // Auto-save queues missing songs every sync; a failed backup stays failed.
        assertFalse(DownloadBackup.wantsLater(SocketTimeoutException(), automatic = true, backup = false))
        assertFalse(DownloadBackup.wantsLater(SocketTimeoutException(), automatic = false, backup = true))
    }

    @Test fun theLaterTryWaitsForWiFi() {
        assertEquals(NetworkType.UNMETERED, OfflineAudioLibrary.networkForDownload(null, null, backup = true))
        assertEquals(NetworkType.CONNECTED, OfflineAudioLibrary.networkForDownload(null, null))
    }

    @Test fun aSavedSongNeedsNoFallback() = runBlocking {
        val folder = temporary.newFolder()
        val saved = File(folder, "$id.m4a").apply { writeBytes(byteArrayOf(9)) }
        assertEquals(saved, OfflineAudioLibrary.transfer(folder, id, false, authorized = { true },
            resolve = { throw AssertionError("Saved song was resolved") },
            fetch = { _, _, _ -> throw AssertionError("Saved song was fetched") }))
    }
}
