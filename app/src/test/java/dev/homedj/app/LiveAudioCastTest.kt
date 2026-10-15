package dev.homedj.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAudioCastTest {
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
        RemoteChannel.sendAudio(DataOutputStream(out), key, challenge, pcm)
        assertArrayEquals(pcm, RemoteChannel.receiveAudio(
            DataInputStream(ByteArrayInputStream(out.toByteArray())), key, challenge
        ))
        val tampered = out.toByteArray().also {
            it[it.lastIndex] = (it.last().toInt() xor 0x40).toByte()
        }
        assertThrows(AEADBadTagException::class.java) {
            RemoteChannel.receiveAudio(DataInputStream(ByteArrayInputStream(tampered)), key, challenge)
        }
        assertThrows(AEADBadTagException::class.java) {
            RemoteChannel.receiveAudio(
                DataInputStream(ByteArrayInputStream(out.toByteArray())), key, ByteArray(16)
            )
        }
    }

    @Test fun rejectsMalformedAudioFrames() {
        val key = RemoteChannel.decodeKey(RemoteChannel.generateKey())!!
        val challenge = ByteArray(16)
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChannel.sendAudio(DataOutputStream(ByteArrayOutputStream()), key, challenge, ByteArray(7))
        }
        val truncated = ByteArrayOutputStream()
        DataOutputStream(truncated).writeInt(100_000)
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChannel.receiveAudio(
                DataInputStream(ByteArrayInputStream(truncated.toByteArray())), key, challenge
            )
        }
    }
}
