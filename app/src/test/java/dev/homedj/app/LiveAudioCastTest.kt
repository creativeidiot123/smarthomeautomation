package dev.homedj.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAudioCastTest {
    @Test fun shutdownClosesCurrentSocketAndIsIdempotent() {
        val lease = CastSocketLease()
        val active = Socket()
        lease.attach(active)
        assertFalse(active.isClosed)

        lease.close()
        lease.close()
        assertTrue(active.isClosed)
    }

    @Test fun shutdownBeforeSocketPublicationClosesAndRejectsLateSocket() {
        val lease = CastSocketLease()
        lease.close()
        val late = Socket()
        assertThrows(IOException::class.java) { lease.attach(late) }
        assertTrue(late.isClosed)
    }

    @Test fun onlyAlignedBoundedPcmPacketsAreAccepted() {
        assertTrue(LiveAudioFormat.validPacket(4))
        assertTrue(LiveAudioFormat.validPacket(LiveAudioFormat.CHUNK_BYTES))
        listOf(-1, 0, 1, 3, 5, LiveAudioFormat.CHUNK_BYTES + 4)
            .forEach { assertFalse(LiveAudioFormat.validPacket(it)) }
    }

    @Test fun framesAreEncryptedAuthenticatedAndCorruptionIsRejected() {
        val key = RemoteChannel.decodeKey(RemoteChannel.generateKey())!!
        val challenge = ByteArray(16) { (it + 2).toByte() }
        val pcm = ByteArray(LiveAudioFormat.CHUNK_BYTES) { (it * 31).toByte() }
        val out = ByteArrayOutputStream()
        RemoteChannel.sendAudio(DataOutputStream(out), key, challenge, 0, pcm)
        assertArrayEquals(pcm, RemoteChannel.receiveAudio(
            DataInputStream(ByteArrayInputStream(out.toByteArray())), key, challenge, 0
        ))
        val tampered = out.toByteArray().also {
            it[it.lastIndex] = (it.last().toInt() xor 0x40).toByte()
        }
        assertThrows(AEADBadTagException::class.java) {
            RemoteChannel.receiveAudio(DataInputStream(ByteArrayInputStream(tampered)), key, challenge, 0)
        }
        assertThrows(AEADBadTagException::class.java) {
            RemoteChannel.receiveAudio(
                DataInputStream(ByteArrayInputStream(out.toByteArray())), key, ByteArray(16), 0
            )
        }
    }

    @Test fun replayedDroppedOrReorderedFramesAreRejected() {
        val key = RemoteChannel.decodeKey(RemoteChannel.generateKey())!!
        val challenge = ByteArray(16) { it.toByte() }
        val frames = (0L..1L).map { sequence ->
            ByteArrayOutputStream().also {
                RemoteChannel.sendAudio(DataOutputStream(it), key, challenge, sequence, ByteArray(8))
            }.toByteArray()
        }
        val received = DataInputStream(ByteArrayInputStream(frames[0] + frames[1]))
        RemoteChannel.receiveAudio(received, key, challenge, 0)
        RemoteChannel.receiveAudio(received, key, challenge, 1)
        // Frame 0 replayed (or frame 1 arriving first) in position 1 fails authentication.
        assertThrows(AEADBadTagException::class.java) {
            RemoteChannel.receiveAudio(DataInputStream(ByteArrayInputStream(frames[0])), key, challenge, 1)
        }
        assertThrows(AEADBadTagException::class.java) {
            RemoteChannel.receiveAudio(DataInputStream(ByteArrayInputStream(frames[1])), key, challenge, 0)
        }
        // A command frame cannot be spliced into the audio stream.
        val command = ByteArrayOutputStream().also {
            RemoteChannel.send(DataOutputStream(it), key, challenge, org.json.JSONObject().put("a", "bcde"))
        }.toByteArray()
        assertThrows(AEADBadTagException::class.java) {
            RemoteChannel.receiveAudio(DataInputStream(ByteArrayInputStream(command)), key, challenge, 0)
        }
    }

    @Test fun rejectsMalformedAudioFrames() {
        val key = RemoteChannel.decodeKey(RemoteChannel.generateKey())!!
        val challenge = ByteArray(16)
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChannel.sendAudio(DataOutputStream(ByteArrayOutputStream()), key, challenge, 0, ByteArray(7))
        }
        val truncated = ByteArrayOutputStream()
        DataOutputStream(truncated).writeInt(100_000)
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChannel.receiveAudio(
                DataInputStream(ByteArrayInputStream(truncated.toByteArray())), key, challenge, 0
            )
        }
    }
}
