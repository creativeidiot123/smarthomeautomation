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
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

/** YouTube refuses this track (private, restricted, DRM-only, bot check); retrying soon will not help. */
internal class TrackUnavailableException(message: String) : IOException(message)

/** Resolves public audio in-process, without account cookies, shell commands, or an external app. */
internal object NativeAudioSource {
    internal data class Link(val url: String, val extension: String, val itag: Int)
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")

    /** NewPipe's DownloaderImpl.USER_AGENT: YouTube serves extractor pages for a desktop browser. */
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

    /** NewPipe's YoutubeHttpDataSource POST body for /videoplayback requests. */
    internal val STREAM_POST_BODY = byteArrayOf(0x78, 0)

    fun validId(id: String) = videoIdPattern.matches(id)

    /** No signed media URL is written to disk or included in diagnostic logging. */
    internal fun acceptedCdnUrl(value: String): Boolean {
        val uri = try { URI(value) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return false
        return uri.scheme?.lowercase(java.util.Locale.ROOT) == "https" &&
            uri.userInfo == null && (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))
    }

    /**
     * Same request headers as NewPipe's YoutubeHttpDataSource: visionOS links need the visionOS
     * agent, other links a desktop browser, web-client links browser CORS headers.
     */
    internal fun streamHeaders(url: String): Map<String, String> = buildMap {
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            put("Origin", "https://www.youtube.com")
            put("Referer", "https://www.youtube.com")
            put("Sec-Fetch-Dest", "empty")
            put("Sec-Fetch-Mode", "cors")
            put("Sec-Fetch-Site", "cross-site")
        }
        put("TE", "trailers")
        put("User-Agent", if (YoutubeParsingHelper.isVisionOsStreamingUrl(url))
            YoutubeParsingHelper.getVisionOsUserAgent(null) else BROWSER_USER_AGENT)
        put("Accept-Encoding", "identity")
    }

    /**
     * NewPipe's DownloaderImpl: a browser agent unless the extractor names its own, then the
     * extractor's headers replace defaults. Its consent cookie is kept; we never hold account cookies.
     */
    internal fun extractorHeaders(requested: Map<String, List<String>>): Map<String, List<String>> {
        val headers = linkedMapOf("User-Agent" to listOf(BROWSER_USER_AGENT))
        requested.forEach { (key, values) ->
            if (key.equals("Host", ignoreCase = true) || key.equals("Authorization", ignoreCase = true)) {
                return@forEach
            }
            headers.keys.firstOrNull { it.equals(key, ignoreCase = true) }?.let(headers::remove)
            headers[key] = values
        }
        return headers
    }

    /** Without a PoToken provider, ANDROID-client links can be cut off mid-song; visionOS links need none. */
    internal fun preferredStream(candidates: List<Pair<String, Int>>): Pair<String, Int>? =
        candidates.maxWithOrNull(compareBy({ YoutubeParsingHelper.isVisionOsStreamingUrl(it.first) }, { it.second }))

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
                        connection.readTimeout = 30_000
                        connection.instanceFollowRedirects = true
                        val method = request.httpMethod().uppercase(java.util.Locale.ROOT)
                        if (method !in setOf("GET", "POST", "HEAD")) throw IOException("Unsupported request method")
                        connection.requestMethod = method
                        extractorHeaders(request.headers()).forEach { (key, values) ->
                            values.forEachIndexed { index, value ->
                                if (index == 0) connection.setRequestProperty(key, value)
                                else connection.addRequestProperty(key, value)
                            }
                        }
                        if (method == "POST") {
                            connection.doOutput = true
                            connection.outputStream.use { it.write(request.dataToSend() ?: byteArrayOf()) }
                        }
                        try {
                            val status = connection.responseCode
                            if (status == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
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
        val info = try {
            StreamInfo.getInfo("https://www.youtube.com/watch?v=$id")
        } catch (refused: ContentNotAvailableException) {
            throw TrackUnavailableException(refused.javaClass.simpleName)
        } catch (refused: ContentNotSupportedException) {
            throw TrackUnavailableException(refused.javaClass.simpleName)
        } catch (refused: SignInConfirmNotBotException) {
            throw TrackUnavailableException(refused.javaClass.simpleName)
        } catch (refused: ReCaptchaException) {
            throw TrackUnavailableException(refused.javaClass.simpleName)
        } catch (failure: ExtractionException) {
            throw IOException("Extraction failed: " + failure.javaClass.simpleName)
        }
        val candidates = info.audioStreams.orEmpty().filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                acceptedCdnUrl(it.content) &&
                it.format?.suffix in setOf("m4a", "webm", "opus")
        }
        val preferred = preferredStream(candidates.map { it.content to it.averageBitrate })
            ?: throw TrackUnavailableException("No unprotected, directly downloadable audio stream available")
        val chosen = candidates.first { it.content == preferred.first }
        return Link(chosen.content, chosen.format?.suffix ?: "m4a", chosen.itag)
    }
}
