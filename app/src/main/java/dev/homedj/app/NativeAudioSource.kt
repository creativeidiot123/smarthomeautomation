package dev.homedj.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

/** Resolves public audio in-process, without cookies, shell commands, or an external app. */
internal object NativeAudioSource {
    internal data class Link(val url: String, val extension: String)
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")

    fun validId(id: String) = videoIdPattern.matches(id)

    /** No signed media URL is written to disk or included in diagnostic logging. */
    internal fun acceptedCdnUrl(value: String): Boolean {
        val uri = try { URI(value) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return false
        return uri.scheme?.lowercase(java.util.Locale.ROOT) == "https" &&
            uri.userInfo == null && (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))
    }

    @Volatile private var initialized = false

    private fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(object : Downloader() {
                    override fun execute(request: Request): Response {
                        val url = URL(request.url())
                        if (url.protocol != "https") throw IOException("Extractor requested an insecure URL")
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 12_000
                        connection.readTimeout = 15_000
                        connection.instanceFollowRedirects = true
                        val method = request.httpMethod().uppercase(java.util.Locale.ROOT)
                        if (method !in setOf("GET", "POST", "HEAD")) throw IOException("Unsupported request method")
                        connection.requestMethod = method
                        request.headers().forEach { (key, values) ->
                            if (key.equals("Host", ignoreCase = true) ||
                                key.equals("Cookie", ignoreCase = true) ||
                                key.equals("Authorization", ignoreCase = true)) return@forEach
                            values.forEach { connection.addRequestProperty(key, it) }
                        }
                        if (method == "POST") {
                            connection.doOutput = true
                            connection.outputStream.use { it.write(request.dataToSend() ?: byteArrayOf()) }
                        }
                        try {
                            val status = connection.responseCode
                            val body = if (method == "HEAD") "" else {
                                val stream = if (status < 400) connection.inputStream else connection.errorStream
                                stream?.use { input ->
                                    val bytes = ByteArrayOutputStream()
                                    val buffer = ByteArray(8_192)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count == -1) break
                                        if (bytes.size() + count > 5_000_000) throw IOException("Extractor response too large")
                                        bytes.write(buffer, 0, count)
                                    }
                                    bytes.toString(Charsets.UTF_8.name())
                                }.orEmpty()
                            }
                            return Response(status, connection.responseMessage.orEmpty(),
                                connection.headerFields.filterKeys { it != null }
                                    .mapKeys { it.key ?: "" }, body, connection.url.toString())
                        } finally {
                            connection.disconnect()
                        }
                    }
                })
                initialized = true
            }
        }
    }

    suspend fun resolve(id: String): Link = withContext(Dispatchers.IO) { resolveBlocking(id) }

    /** Media3's ResolvingDataSource invokes this on its background loader thread. */
    internal fun resolveBlocking(id: String): Link {
        require(validId(id)) { "Invalid YouTube video ID" }
        initialize()
        val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$id")
        val candidates = info.audioStreams.orEmpty().filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                acceptedCdnUrl(it.content) &&
                it.format?.suffix in setOf("m4a", "webm", "opus")
        }
        val chosen = candidates.maxByOrNull { it.averageBitrate }
            ?: throw IOException("No unprotected, directly downloadable audio stream available")
        return Link(chosen.content, chosen.format?.suffix ?: "m4a")
    }
}
