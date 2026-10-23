package dev.homedj.app

import java.io.IOException
import java.net.SocketTimeoutException
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Whole playlists past one batch, failed songs skipped, and failures shown on both phones. */
class PlaylistDownloadsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun song(id: String) = YouTubeTrack(id, "Song $id")
    private val playlist = listOf(song("aaaaaaaaaaa"), song("bbbbbbbbbbb"), song("ccccccccccc"),
        song("ddddddddddd"), song("aaaaaaaaaaa"), YouTubeTrack("bad id", "Not a video"))

    @Test fun aTopUpQueuesOnlySongsNotSavedPendingOrFailed() {
        val next = PlaylistDownloads.nextSongs(playlist, pending = setOf("bbbbbbbbbbb"),
            failed = setOf("ccccccccccc")) { it == "aaaaaaaaaaa" }
        assertEquals(listOf("ddddddddddd"), next?.map { it.id })
    }

    @Test fun aPlaylistIsDoneWhenEverySongIsSavedOrFailedOrItIsGone() {
        assertNull(PlaylistDownloads.nextSongs(playlist, emptySet(), setOf("ccccccccccc", "ddddddddddd")) {
            it == "aaaaaaaaaaa" || it == "bbbbbbbbbbb"
        })
        assertNull(PlaylistDownloads.nextSongs(null, emptySet(), emptySet()) { false })
        // Songs still pending are not done yet, but there is nothing more to queue for them.
        assertNull(PlaylistDownloads.nextSongs(playlist, setOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc",
            "ddddddddddd"), emptySet()) { false })
    }

    @Test fun theBatchMessageSaysTheRestFollow() {
        assertEquals("", batchNote(0))
        assertEquals(" 1 more follows automatically.", batchNote(1))
        assertEquals(" The other 350 follow automatically.", batchNote(350))
    }

    @Test fun downloadErrorsLeaveTheRunWithoutWaitingForBackoff() {
        val failures = listOf(SocketTimeoutException("read timed out"), IOException("Extraction failed"),
            BotCheckException("SignInConfirmNotBotException"), BotCheckException("ReCaptchaException"),
            TrackUnavailableException("ContentNotAvailableException"), DownloadRejected("Storage budget"))
        failures.forEach { failure ->
            // Includes work already in backoff from an older build.
            (0..5).forEach { attempt -> assertFalse(OfflineAudioLibrary.retryable(failure, attempt)) }
        }
    }

    @Test fun failedSongRowsReachTheOnePlusAndOlderSamsungsSendNone() {
        val row = JSONObject().put("id", "aaaaaaaaaaa").put("title", "One")
        assertTrue(remoteSong(JSONObject(row.toString()).put("failed", true)).failed)
        assertFalse(remoteSong(row).failed)
    }

    @Test fun interruptedSetupRemembersThePlaylistBeforeAnySongIsQueued() = runBlocking {
        val intent = temporary.newFile()
        try {
            PlaylistDownloads.queueBatch(playlist, remember = { intent.writeText("PL1") }, enqueue = {
                throw IOException("Process stopped during enqueue")
            })
            throw AssertionError("Interrupted setup reported success")
        } catch (_: IOException) {
            assertEquals("PL1", intent.readText())
        }
    }

    @Test fun failedIntentWriteQueuesNothing() = runBlocking {
        var queued = 0
        try {
            PlaylistDownloads.queueBatch(playlist, remember = { throw IOException("Disk full") },
                enqueue = { queued++; true })
            throw AssertionError("Failed intent write reported success")
        } catch (_: IOException) {
            assertEquals(0, queued)
        }
    }

    @Test fun recoveryReadsCommittedFilesAndQueuesOnlyTheUnfinishedRest() = runBlocking {
        val folder = temporary.newFolder()
        File(folder, "aaaaaaaaaaa.m4a").writeBytes(byteArrayOf(1))
        // An incomplete song is never mistaken for a playable file after a fresh scan.
        File(folder, "ddddddddddd.140.part").writeBytes(byteArrayOf(1))
        val saved = OfflineAudioLibrary.savedFiles(folder).mapTo(hashSetOf()) { it.nameWithoutExtension }
        val missing = PlaylistDownloads.nextSongs(playlist, pending = setOf("bbbbbbbbbbb"),
            failed = setOf("ccccccccccc"), saved::contains).orEmpty()
        val queued = mutableListOf<String>()
        val result = PlaylistDownloads.queueBatch(missing, remember = {}, enqueue = { queued.add(it.id) })
        assertEquals(listOf("ddddddddddd"), queued)
        assertEquals(1, result.queued)
        assertEquals(0, result.later)
        assertEquals(listOf("aaaaaaaaaaa"), OfflineAudioLibrary.savedFiles(folder).map { it.nameWithoutExtension })
    }

    @Test fun setupBoundsTheBatchAndRetainsTheRest() = runBlocking {
        val missing = List(PlaylistDownloads.BATCH + 4) { song(it.toString().padStart(11, 'a')) }
        val queued = mutableListOf<String>()
        val result = PlaylistDownloads.queueBatch(missing, remember = {}, enqueue = { queued.add(it.id) })
        assertEquals(missing.take(PlaylistDownloads.BATCH).map { it.id }, queued)
        assertEquals(PlaylistDownloads.BATCH, result.queued)
        assertEquals(4, result.later)
    }
}
