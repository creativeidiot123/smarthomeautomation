package dev.homedj.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.AEADBadTagException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteChannelTest {
    @Test fun encryptedRoundTripAndTamperRejection() {
        val key = RemoteChannel.decodeKey(RemoteChannel.generateKey())!!
        val challenge = ByteArray(16) { it.toByte() }
        val bytes = ByteArrayOutputStream()
        RemoteChannel.send(DataOutputStream(bytes), key, challenge, JSONObject().put("command", "next"))
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals("next", RemoteChannel.receive(input, key, challenge).getString("command"))

        val flipped = bytes.toByteArray().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            RemoteChannel.receive(DataInputStream(ByteArrayInputStream(flipped)), key, challenge)
        }
        assertThrows(AEADBadTagException::class.java) {
            RemoteChannel.receive(DataInputStream(ByteArrayInputStream(bytes.toByteArray())), key, ByteArray(16))
        }
    }

    @Test fun rejectsInvalidFramesAndKeys() {
        assertNull(RemoteChannel.decodeKey("not-a-key"))
        val truncated = ByteArrayOutputStream()
        DataOutputStream(truncated).writeInt(999999)
        val key = RemoteChannel.decodeKey(RemoteChannel.generateKey())!!
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChannel.receive(DataInputStream(ByteArrayInputStream(truncated.toByteArray())), key, ByteArray(16))
        }
    }

    @Test fun framesUpTo128KbRoundTripAndLargerAreRefused() {
        val key = RemoteChannel.decodeKey(RemoteChannel.generateKey())!!
        val challenge = ByteArray(16)
        val bytes = ByteArrayOutputStream()
        val large = "x".repeat(100_000)
        RemoteChannel.send(DataOutputStream(bytes), key, challenge, JSONObject().put("lights", large))
        assertEquals(large, RemoteChannel.receive(DataInputStream(ByteArrayInputStream(bytes.toByteArray())), key, challenge)
            .getString("lights"))
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChannel.send(DataOutputStream(ByteArrayOutputStream()), key, challenge, JSONObject().put("lights", "x".repeat(131_072)))
        }
    }

    @Test fun privateLanOnly() {
        assertTrue(RemotePrefs.validPrivateIpv4("192.168.1.24"))
        assertTrue(RemotePrefs.validPrivateIpv4("10.0.0.1"))
        assertTrue(RemotePrefs.validPrivateIpv4("172.31.3.1"))
        listOf("8.8.8.8", "localhost", "127.0.0.1", "192.168.1.999", "192.168.1.2:80", "172.32.0.1")
            .forEach { assertFalse(it, RemotePrefs.validPrivateIpv4(it)) }
    }
}
