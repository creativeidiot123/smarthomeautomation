package dev.homedj.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
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

    /** [replyTimeoutMillis] bounds Samsung's work on the request, e.g. reading a playlist online. */
    fun exchange(
        address: String, key: SecretKeySpec, request: JSONObject, replyTimeoutMillis: Int = 5_000,
    ): JSONObject =
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, PORT), 3_000)
            socket.soTimeout = 5_000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val challenge = ByteArray(16).also { input.readFully(it) }
            send(output, key, challenge, request)
            socket.soTimeout = replyTimeoutMillis
            receive(input, key, challenge)
        }

    /** One authenticated command, optionally followed by encrypted PCM frames on the same socket. */
    suspend fun serve(
        input: DataInputStream, output: DataOutputStream, key: SecretKeySpec,
        action: suspend (JSONObject) -> JSONObject,
        onPcm: suspend (() -> ByteArray) -> Unit = {},
    ) {
        val challenge = ByteArray(16).also(random::nextBytes)
        output.write(challenge)
        output.flush()
        val request = receive(input, key, challenge)
        val reply = action(request)
        send(output, key, challenge, reply)
        if (request.optString("command") == "audioStart" && reply.optBoolean("ok")) {
            var sequence = 0L
            onPcm { receiveAudio(input, key, challenge, sequence++) }
        }
    }

    /** Caller owns AudioRecord and closes it to interrupt this blocking loop on shutdown. */
    fun streamAudio(host: String, key: SecretKeySpec, onSocket: (Socket) -> Unit, next: () -> ByteArray?) {
        Socket().use { socket ->
            onSocket(socket)
            socket.connect(InetSocketAddress(host, PORT), 3_000)
            socket.tcpNoDelay = true
            socket.soTimeout = 5_000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val challenge = ByteArray(16).also { input.readFully(it) }
            send(output, key, challenge, JSONObject().put("command", "audioStart"))
            if (!receive(input, key, challenge).optBoolean("ok")) {
                throw IOException("Samsung rejected audio casting")
            }
            var sequence = 0L
            while (!socket.isClosed) {
                sendAudio(output, key, challenge, sequence++, next() ?: break)
            }
        }
    }

    /** Each PCM frame authenticates its position, so a LAN peer cannot replay, drop or reorder frames. */
    internal fun audioAad(challenge: ByteArray, sequence: Long): ByteArray =
        ByteBuffer.allocate(challenge.size + Long.SIZE_BYTES).put(challenge).putLong(sequence).array()

    internal fun sendAudio(
        output: DataOutputStream, key: SecretKeySpec, challenge: ByteArray, sequence: Long, pcm: ByteArray,
    ) {
        require(LiveAudioFormat.validPacket(pcm.size)) { "Invalid audio frame" }
        sendBytes(output, key, audioAad(challenge, sequence), pcm)
    }

    internal fun receiveAudio(
        input: DataInputStream, key: SecretKeySpec, challenge: ByteArray, sequence: Long,
    ): ByteArray {
        val data = receiveBytes(input, key, audioAad(challenge, sequence))
        if (!LiveAudioFormat.validPacket(data.size)) throw IOException("Invalid PCM packet")
        return data
    }

    internal fun send(
        output: DataOutputStream,
        key: SecretKeySpec,
        challenge: ByteArray,
        message: JSONObject,
    ) {
        sendBytes(output, key, challenge, message.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sendBytes(
        output: DataOutputStream, key: SecretKeySpec, challenge: ByteArray, bytes: ByteArray,
    ) {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(challenge)
        val encrypted = cipher.doFinal(bytes)
        val length = nonce.size + encrypted.size
        require(length <= MAX_FRAME) { "Remote message too large" }
        output.writeInt(length)
        output.write(nonce)
        output.write(encrypted)
        output.flush()
    }

    internal fun receive(input: DataInputStream, key: SecretKeySpec, challenge: ByteArray): JSONObject =
        JSONObject(String(receiveBytes(input, key, challenge), Charsets.UTF_8))

    private fun receiveBytes(input: DataInputStream, key: SecretKeySpec, challenge: ByteArray): ByteArray {
        val size = input.readInt()
        require(size in 29..MAX_FRAME) { "Invalid remote message size" }
        val nonce = ByteArray(12).also { input.readFully(it) }
        val ciphertext = ByteArray(size - 12).also { input.readFully(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(challenge)
        return cipher.doFinal(ciphertext)
    }
}
