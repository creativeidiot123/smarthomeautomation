package dev.homedj.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A transfer that cannot succeed by resuming (budget, consent, bad response); its partial is discarded. */
internal class DownloadRejected(message: String) : IOException(message)

/**
 * NewPipe-downloader-style ranged transfer: 512 KiB Range requests appended to a partial file,
 * so a later worker attempt resumes where an interrupted one stopped. Requests use the same
 * shape as playback (NewPipe's YoutubeHttpDataSource) and must stay on the GoogleVideo CDN.
 */
internal object AudioTransfer {
    const val CHUNK_BYTES = 512L * 1024

    internal data class ContentRange(val start: Long, val end: Long, val total: Long)

    private val contentRangePattern = Regex("""bytes (\d+)-(\d+)/(\d+)""")

    internal fun parseContentRange(value: String?): ContentRange? {
        val match = contentRangePattern.matchEntire(value?.trim() ?: return null) ?: return null
        val (start, end, total) = match.destructured.toList().map { it.toLongOrNull() ?: return null }
        return ContentRange(start, end, total).takeIf { start <= end && end < total }
    }

    /**
     * Appends the remaining bytes of [url] to [partial] and returns the complete size.
     * [checkpoint] runs before every request with the known total (or -1) and may throw to stop.
     */
    suspend fun fetch(url: String, partial: File, checkpoint: suspend (total: Long) -> Unit): Long {
        var total = -1L
        var request = 0L
        FileOutputStream(partial, true).buffered().use { output ->
            var offset = partial.length()
            while (total < 0 || offset < total) {
                coroutineContext.ensureActive()
                checkpoint(total)
                val numbered = HomeDjMediaResolver.withRequestNumber(url, request++)
                val connection = URL(numbered).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 30_000
                    connection.instanceFollowRedirects = true
                    connection.requestMethod = "POST"
                    NativeAudioSource.streamHeaders(numbered).forEach(connection::setRequestProperty)
                    connection.setRequestProperty("Range", "bytes=$offset-${offset + CHUNK_BYTES - 1}")
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(NativeAudioSource.STREAM_POST_BODY.size)
                    connection.outputStream.use { it.write(NativeAudioSource.STREAM_POST_BODY) }
                    val status = connection.responseCode
                    if (!NativeAudioSource.acceptedCdnUrl(connection.url.toString())) {
                        throw DownloadRejected("Audio redirected off the media CDN")
                    }
                    if (status == 416) throw DownloadRejected("Partial audio no longer matches the stream")
                    if (status != 206) throw IOException("Audio HTTP status $status")
                    val contentType = connection.contentType?.lowercase(java.util.Locale.ROOT).orEmpty()
                    if (!contentType.startsWith("audio/") && !contentType.startsWith("video/")) {
                        throw DownloadRejected("Media server returned a non-audio response")
                    }
                    val range = parseContentRange(connection.getHeaderField("Content-Range"))
                        ?: throw IOException("Missing Content-Range")
                    if (range.start != offset || total >= 0 && range.total != total) {
                        throw DownloadRejected("Audio range does not continue the partial file")
                    }
                    if (total < 0) {
                        total = range.total
                        checkpoint(total)
                    }
                    val expected = range.end - range.start + 1
                    var received = 0L
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (received < expected) {
                            coroutineContext.ensureActive()
                            val size = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - received).toInt())
                            if (size < 0) break
                            output.write(buffer, 0, size)
                            received += size
                        }
                    }
                    output.flush()
                    offset += received
                    if (received != expected) throw IOException("Audio chunk incomplete")
                } finally {
                    connection.disconnect()
                }
            }
        }
        return total
    }
}
