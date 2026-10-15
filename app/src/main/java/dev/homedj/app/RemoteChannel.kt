package dev.homedj.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Each TCP connection receives a fresh server challenge. AES-GCM authenticates and encrypts
 * the request AND response against that challenge. Captured commands cannot be replayed.
 * The pairing secret is entered out of band, never sent over the network.
 */
internal object RemoteChannel {
    private const val PORT = 47188
    private const val MAX_FRAME = 65_536
    private val random = SecureRandom()

    fun generateKey(): String = ByteArray(32).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    fun decodeKey(encoded: String): SecretKeySpec? {
        val decoded = runCatching { Base64.getUrlDecoder().decode(encoded.trim()) }.getOrNull()
        return decoded?.takeIf { it.size == 32 }?.let { SecretKeySpec(it, "AES") }
    }

    fun exchange(address: String, key: SecretKeySpec, request: JSONObject): JSONObject =
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, PORT), 3_000)
            socket.soTimeout = 5_000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val challenge = ByteArray(16).also { input.readFully(it) }
            send(output, key, challenge, request)
            receive(input, key, challenge)
        }

    fun serve(input: DataInputStream, output: DataOutputStream, key: SecretKeySpec, action: (JSONObject) -> JSONObject) {
        val challenge = ByteArray(16).also(random::nextBytes)
        output.write(challenge)
        output.flush()
        val request = receive(input, key, challenge)
        send(output, key, challenge, action(request))
    }

    internal fun send(
        output: DataOutputStream,
        key: SecretKeySpec,
        challenge: ByteArray,
        message: JSONObject,
    ) {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(challenge)
        val encrypted = cipher.doFinal(message.toString().toByteArray(Charsets.UTF_8))
        val length = nonce.size + encrypted.size
        require(length <= MAX_FRAME) { "Remote message too large" }
        output.writeInt(length)
        output.write(nonce)
        output.write(encrypted)
        output.flush()
    }

    internal fun receive(input: DataInputStream, key: SecretKeySpec, challenge: ByteArray): JSONObject {
        val size = input.readInt()
        require(size in 29..MAX_FRAME) { "Invalid remote message size" }
        val nonce = ByteArray(12).also { input.readFully(it) }
        val ciphertext = ByteArray(size - 12).also { input.readFully(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(challenge)
        val plaintext = cipher.doFinal(ciphertext)
        return JSONObject(String(plaintext, Charsets.UTF_8))
    }
}
