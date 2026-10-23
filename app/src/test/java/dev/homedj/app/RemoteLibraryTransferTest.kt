package dev.homedj.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLibraryTransferTest {
    @Test fun anOrdinaryPlaylistArrivesInOneReplyWithoutAFortySongLimit() = runBlocking {
        val songs = List(260) { YouTubeTrack("song$it", "Song $it") }
        var requests = 0
        val result = loadRemoteBrowse("tracks") { offset ->
            requests++
            remoteLibraryPage(songs, offset, SavedPlaylistCodec::trackJson)
        }
        assertEquals(1, requests)
        assertEquals(songs.map { it.id }, result.songs.map { it.id })
    }

    @Test fun largeUnicodeListsStayWithinTheExistingEncryptedFrameLimit() = runBlocking {
        val songs = List(500) { YouTubeTrack("song$it", "音楽 🎵".repeat(60)) }
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val challenge = ByteArray(32)
        var requests = 0
        val result = loadRemoteBrowse("tracks") { offset ->
            requests++
            val reply = remoteLibraryPage(songs, offset, SavedPlaylistCodec::trackJson)
            assertTrue(reply.toString().toByteArray(Charsets.UTF_8).size <= 60_000)
            val output = ByteArrayOutputStream()
            RemoteChannel.send(DataOutputStream(output), key, challenge, reply)
            RemoteChannel.receive(DataInputStream(ByteArrayInputStream(output.toByteArray())), key, challenge)
        }
        assertTrue(requests > 1)
        assertEquals(songs.map { it.id }, result.songs.map { it.id })
        assertEquals(500, result.total)
    }

    @Test fun anOversizedItemIsRejectedWithoutSendingAnOversizedFrame() {
        val reply = remoteLibraryPage(listOf("x".repeat(70_000)), 0) { JSONObject().put("title", it) }
        assertFalse(reply.optBoolean("ok"))
        assertEquals("Library item too large", reply.optString("error"))
    }
}
