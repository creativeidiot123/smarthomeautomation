package dev.homedj.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** YouTube refuses this track (private, restricted, DRM-only, bot check); playback skips it. */
internal open class TrackUnavailableException(message: String) : IOException(message)

/**
 * YouTube's "confirm you're not a bot" or reCAPTCHA: a rate limit on this phone's address, not
 * the song. Playback and the current download run skip it; an explicit save can try again later.
 */
internal class BotCheckException(message: String) : TrackUnavailableException(message)

/** One regular-YouTube video search result: enough to judge whether it is the same song. */
internal data class SearchHit(val id: String, val title: String, val uploader: String, val durationSeconds: Int)

/** A public playlist read by link: its metadata and the songs that have valid video IDs. */
internal data class LinkedPlaylist(val playlist: YouTubePlaylist, val tracks: List<YouTubeTrack>)

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

    /** The extractor lists songs as watch URLs; only the `v` parameter names the song. */
    internal fun idFromWatchUrl(url: String): String? =
        Regex("[?&]v=([A-Za-z0-9_-]{11})(?:&|$)").find(url)?.groupValues?.get(1)

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
        rankedStreams(candidates).firstOrNull()

    /** Best first; downloads fall back down this list when a stream fails, playback takes the first. */
    internal fun rankedStreams(candidates: List<Pair<String, Int>>): List<Pair<String, Int>> =
        candidates.distinctBy { it.first }.sortedWith(
            compareByDescending<Pair<String, Int>> { YoutubeParsingHelper.isVisionOsStreamingUrl(it.first) }
                .thenByDescending { it.second })

    /** A download tries at most this many of the offered streams per extraction. */
    internal const val MAX_FALLBACK_STREAMS = 3

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

    /** The best stream first, then up to [MAX_FALLBACK_STREAMS] in all for a download to fall back to. */
    suspend fun resolveAll(id: String): List<Link> = withContext(Dispatchers.IO) {
        resolveRanked(id).take(MAX_FALLBACK_STREAMS)
    }

    /** Media3's ResolvingDataSource invokes this on its background loader thread. */
    internal fun resolveBlocking(id: String): Link = resolveRanked(id).first()

    private fun resolveRanked(id: String): List<Link> {
        require(validId(id)) { "Invalid YouTube video ID" }
        initialize()
        val info = try {
            StreamInfo.getInfo("https://www.youtube.com/watch?v=$id")
        } catch (refused: ContentNotAvailableException) {
            throw TrackUnavailableException(refused.javaClass.simpleName)
        } catch (refused: ContentNotSupportedException) {
            throw TrackUnavailableException(refused.javaClass.simpleName)
        } catch (refused: SignInConfirmNotBotException) {
            throw BotCheckException(refused.javaClass.simpleName)
        } catch (refused: ReCaptchaException) {
            throw BotCheckException(refused.javaClass.simpleName)
        } catch (failure: ExtractionException) {
            throw IOException("Extraction failed: " + failure.javaClass.simpleName)
        }
        val candidates = info.audioStreams.orEmpty().filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                acceptedCdnUrl(it.content) &&
                it.format?.suffix in setOf("m4a", "webm", "opus")
        }
        val ranked = rankedStreams(candidates.map { it.content to it.averageBitrate })
        if (ranked.isEmpty()) throw TrackUnavailableException("No unprotected, directly downloadable audio stream available")
        return ranked.map { (url, _) ->
            val chosen = candidates.first { it.content == url }
            Link(chosen.content, chosen.format?.suffix ?: "m4a", chosen.itag)
        }
    }

    /**
     * Reads a public or unlisted playlist without an account, for a Samsung with no YouTube
     * token or a link sent from the OnePlus. Null when YouTube says it is private or gone.
     */
    suspend fun playlist(id: String): LinkedPlaylist? = withContext(Dispatchers.IO) {
        initialize()
        val url = "https://www.youtube.com/playlist?list=$id"
        try {
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
            val items = info.relatedItems.toMutableList()
            var next = info.nextPage
            var pages = 1
            while (Page.isValid(next) && items.size < MAX_LINKED_TRACKS && pages < MAX_LINKED_PAGES) {
                val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next)
                items += more.items
                next = more.nextPage
                pages++
            }
            val tracks = items.mapNotNull { item ->
                val songId = idFromWatchUrl(item.url ?: "") ?: return@mapNotNull null
                YouTubeTrack(songId, (item.name ?: "").ifBlank { songId }.take(300),
                    cleanArtist(item.uploaderName), item.duration.coerceIn(0L, MAX_TRACK_SECONDS.toLong()).toInt())
            }.distinctBy(YouTubeTrack::id).take(MAX_LINKED_TRACKS)
            val title = (info.name ?: "").ifBlank { "YouTube playlist" }.take(300)
            val cover = info.thumbnails.orEmpty().mapNotNull { youTubeImageUrl(it.url) }.lastOrNull()
            LinkedPlaylist(YouTubePlaylist(id, title, tracks.size, cover.orEmpty()), tracks)
        } catch (gone: ContentNotAvailableException) {
            null
        } catch (failure: ExtractionException) {
            throw IOException("Playlist extraction failed: " + failure.javaClass.simpleName)
        }
    }

    /** Regular YouTube video results (no Shorts or live streams) for [query], in YouTube's order. */
    suspend fun searchVideos(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        initialize()
        val items = try {
            val handler = ServiceList.YouTube.searchQHFactory
                .fromQuery(query.take(200), listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
            SearchInfo.getInfo(ServiceList.YouTube, handler).relatedItems
        } catch (refused: ReCaptchaException) {
            throw BotCheckException(refused.javaClass.simpleName)
        } catch (failure: ExtractionException) {
            throw IOException("Search failed: " + failure.javaClass.simpleName)
        }
        items.filterIsInstance<StreamInfoItem>()
            .filter { it.streamType == StreamType.VIDEO_STREAM && !it.isShortFormContent }
            .mapNotNull { item ->
                val id = idFromWatchUrl(item.url ?: "") ?: return@mapNotNull null
                SearchHit(id, (item.name ?: "").take(300), item.uploaderName.orEmpty().take(200),
                    item.duration.coerceIn(0L, MAX_TRACK_SECONDS.toLong()).toInt())
            }
    }

    private const val MAX_LINKED_TRACKS = MAX_PLAYLIST_SONGS
    // YouTube sends 100 songs a page; spare pages cover pages thinned by unavailable songs.
    private const val MAX_LINKED_PAGES = 25
}
