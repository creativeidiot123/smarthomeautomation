package dev.homedj.app

import java.security.MessageDigest
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import javax.crypto.AEADBadTagException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemotePairingTest {
    @Test fun shortCodeIs128RandomBitsAndAcceptsGroupedLowercaseInput() {
        // 128 zero bits in RFC 4648 Base32, with two zero padding bits in the final symbol.
        val code = "A".repeat(26)
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("HomeDJ pairing v1\u0000".toByteArray() + ByteArray(16))
        assertArrayEquals(expected, requireNotNull(RemoteChannel.decodeKey(code)).encoded)
        assertArrayEquals(expected, requireNotNull(RemoteChannel.decodeKey(code.lowercase().chunked(5).joinToString(" "))).encoded)
        val generated = List(32) { RemoteChannel.generateKey() }
        assertEquals(32, generated.toSet().size)
        generated.forEach {
            assertEquals(26, it.length)
            assertTrue(it.all { char -> char in 'A'..'Z' || char in '2'..'7' })
            assertNotNull(RemoteChannel.decodeKey(it))
        }
    }

    @Test fun legacyKeysKeepExactlyTheSameEncryptionKey() {
        val bytes = ByteArray(32) { it.toByte() }
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        assertArrayEquals(bytes, requireNotNull(RemoteChannel.decodeKey(code)).encoded)
        assertArrayEquals(bytes, requireNotNull(RemoteChannel.decodeKey(" $code ")).encoded)
    }

    @Test fun base32DecodesAnIndependentNonzeroVector() {
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("HomeDJ pairing v1\u0000".toByteArray() + ByteArray(16) { it.toByte() })
        assertArrayEquals(expected, requireNotNull(RemoteChannel.decodeKey("AAAQEAYEAUDAOCAJBIFQYDIOB4")).encoded)
    }

    @Test fun rejectsMalformedShortCodesWithoutCoercingAmbiguousCharacters() {
        listOf("A".repeat(25), "A".repeat(27), "A".repeat(25) + "B",
            "0".repeat(26), "1".repeat(26), "A".repeat(25) + "-", "A".repeat(1025))
            .forEach { assertNull(RemoteChannel.decodeKey(it)) }
    }

    @Test fun manualPairingNormalizesInputAndOnlyAcceptsHomeDjPort() {
        val input = requireNotNull(remotePairingInput(" 192.168.1.24 ", " 47188 ", "aaaaa aaaaa aaaaa aaaaa aaaaa a"))
        assertEquals("192.168.1.24", input.host)
        assertEquals("A".repeat(26), input.code)
        listOf("", "0", "65536", "80", "47189", "47188.0").forEach {
            assertNull(remotePairingInput("192.168.1.24", it, input.code))
        }
        assertNull(remotePairingInput("8.8.8.8", "47188", input.code))
        assertNull(remotePairingInput("192.168.1.24", "47188", "bad"))
    }

    @Test fun qrRoundTripIncludesOnlyVersionAddressPortAndCode() {
        for (code in listOf("A".repeat(26), Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)))) {
            val payload = requireNotNull(remotePairingPayload("192.168.1.24", code))
            val json = JSONObject(payload)
            assertEquals(setOf("homedjPairing", "host", "port", "code"), json.keys().asSequence().toSet())
            val input = requireNotNull(parseRemotePairingQr(payload))
            assertEquals("192.168.1.24", input.host)
            assertArrayEquals(requireNotNull(RemoteChannel.decodeKey(code)).encoded,
                requireNotNull(RemoteChannel.decodeKey(input.code)).encoded)
        }
    }

    @Test fun rejectsForeignOversizedAndMalformedQrWithoutConnecting() {
        val valid = requireNotNull(remotePairingPayload("10.0.0.1", "A".repeat(26)))
        listOf(null, "", "MT:123456", "https://example.com", "x".repeat(1025), "{}",
            JSONObject(valid).put("homedjPairing", 2).toString(),
            JSONObject(valid).put("host", "8.8.8.8").toString(),
            JSONObject(valid).put("port", 80).toString(),
            JSONObject(valid).put("code", "bad").toString(),
            JSONObject(valid).also { it.remove("host") }.toString(),
            JSONObject(valid).put("host", JSONObject.NULL).toString(),
            JSONObject(valid).put("port", "47188").toString())
            .forEach { assertNull(parseRemotePairingQr(it)) }
        assertNull(remotePairingPayload("Check Wi-Fi", "A".repeat(26)))
    }

    @Test fun displayedQrDecodesToTheOriginalCredentialsWithAQuietZone() {
        val codes = listOf("A".repeat(26), Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)))
        for (code in codes) {
            val payload = requireNotNull(remotePairingPayload("192.168.100.254", code))
            val qr = pairingQrMatrix(payload)
            assertEquals(qr.width, qr.height)
            for (y in 0 until qr.height) for (x in 0 until qr.width) {
                if (x < 4 || y < 4 || x >= qr.width - 4 || y >= qr.height - 4) assertFalse(qr[x, y])
            }
            val bounds = requireNotNull(qr.enclosingRectangle)
            val modules = BitMatrix(bounds[2], bounds[3])
            for (y in 0 until modules.height) for (x in 0 until modules.width) {
                if (qr[x + bounds[0], y + bounds[1]]) modules.set(x, y)
            }
            val decoded = Decoder().decode(modules, null).text
            assertEquals(payload, decoded)
            assertEquals(code, requireNotNull(parseRemotePairingQr(decoded)).code)
        }
    }

    @Test fun newCodeUsesExistingAuthenticatedFramesAndRejectsWrongCodeAndReplay() {
        val key = requireNotNull(RemoteChannel.decodeKey("A".repeat(26)))
        val scanned = requireNotNull(parseRemotePairingQr(requireNotNull(remotePairingPayload("10.0.0.2", "A".repeat(26)))))
        val controllerKey = requireNotNull(RemoteChannel.decodeKey(scanned.code))
        val challenge = ByteArray(16) { it.toByte() }
        val bytes = ByteArrayOutputStream()
        RemoteChannel.send(DataOutputStream(bytes), key, challenge, JSONObject().put("ok", true))
        fun input() = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertTrue(RemoteChannel.receive(input(), controllerKey, challenge).getBoolean("ok"))
        val wrongKey = requireNotNull(RemoteChannel.decodeKey("B" + "A".repeat(25)))
        assertThrows(AEADBadTagException::class.java) { RemoteChannel.receive(input(), wrongKey, challenge) }
        assertThrows(AEADBadTagException::class.java) { RemoteChannel.receive(input(), key, ByteArray(16)) }
        // A temporary failure never requires new credentials: retry a fresh authenticated exchange.
        assertTrue(RemoteChannel.receive(input(), controllerKey, challenge).getBoolean("ok"))
    }
}
