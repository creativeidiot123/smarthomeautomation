package dev.homedj.app

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** 128 random bits, encoded without punctuation; this is a secret, never a user-selected PIN. */
internal object RemotePairingCode {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun generate(): String {
        val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
        return buildString {
            var buffer = 0
            var bits = 0
            for (byte in bytes) {
                buffer = (buffer shl 8) or (byte.toInt() and 255)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    append(ALPHABET[(buffer ushr bits) and 31])
                }
            }
            append(ALPHABET[(buffer shl (5 - bits)) and 31])
        }
    }

    fun normalize(code: String): String {
        val compact = code.filterNot { it.isWhitespace() }.uppercase(java.util.Locale.ROOT)
        return if (compact.length == 26) compact else code.trim()
    }

    fun decode(code: String): SecretKeySpec? {
        val compact = normalize(code)
        if (compact.length != 26 || compact.any { it !in ALPHABET }) return null
        // The last symbol has two unused bits. Reject aliases rather than silently coercing typos.
        if (ALPHABET.indexOf(compact.last()) and 3 != 0) return null
        val bytes = ByteArray(16)
        var buffer = 0
        var bits = 0
        var index = 0
        for (char in compact) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(char)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                bytes[index++] = (buffer ushr bits).toByte()
            }
        }
        // Domain separation fixes this mapping for all future builds; effective strength is 128 bits.
        val key = MessageDigest.getInstance("SHA-256")
            .digest("HomeDJ pairing v1\u0000".toByteArray(Charsets.UTF_8) + bytes)
        return SecretKeySpec(key, "AES")
    }
}

/** Validated manual/QR input. Port stays fixed to the existing authenticated HomeDJ listener. */
internal class RemotePairingInput(val host: String, val code: String)

internal fun remotePairingInput(host: String, port: String, code: String): RemotePairingInput? {
    if (host.length > 32 || port.length > 8 || code.length > 128) return null
    if (!RemotePrefs.validPrivateIpv4(host.trim()) || port.trim() != RemoteChannel.PORT.toString()) return null
    if (RemoteChannel.decodeKey(code) == null) return null
    return RemotePairingInput(host.trim(), RemotePairingCode.normalize(code))
}

/** Local camera transfer only; never put this payload in logs, URLs, files or a network service. */
internal fun remotePairingPayload(host: String, code: String): String? {
    val input = remotePairingInput(host, RemoteChannel.PORT.toString(), code) ?: return null
    return JSONObject().put("homedjPairing", 1).put("host", input.host)
        .put("port", RemoteChannel.PORT).put("code", input.code).toString()
}

internal fun parseRemotePairingQr(payload: String?): RemotePairingInput? {
    if (payload == null || payload.length > 512) return null
    return runCatching {
        val json = JSONObject(payload)
        if (json.opt("homedjPairing") != 1 || json.opt("port") != RemoteChannel.PORT) return null
        remotePairingInput(json.optString("host"), json.optString("port"), json.optString("code"))
    }.getOrNull()
}
