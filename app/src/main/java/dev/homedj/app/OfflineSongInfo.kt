package dev.homedj.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Details and artwork of downloaded songs (`files/offline_info/<id>.json`, `files/offline_art/<id>.jpg`),
 * so the Downloaded view shows titles, artists and art without internet or a playlist listing them.
 * Owned by [OfflineAudioLibrary]: written after an audio file commits, deleted with it. Blocking; IO only.
 */
internal object OfflineSongInfo {
    private const val MAX_INFO_BYTES = 16 * 1024L
    private const val MAX_ART_BYTES = 1024 * 1024L

    fun infoFile(context: Context, id: String): File =
        File(File(context.applicationContext.filesDir, "offline_info"), "$id.json")

    fun artFile(context: Context, id: String): File =
        File(File(context.applicationContext.filesDir, "offline_art"), "$id.jpg")

    /** Atomic temp-and-rename write; a failure leaves the song playable with its catalog title. */
    fun save(context: Context, track: YouTubeTrack) {
        if (!NativeAudioSource.validId(track.id)) return
        val target = infoFile(context, track.id)
        try {
            writeAtomically(target) { it.writeText(SavedPlaylistCodec.trackJson(track).toString(), Charsets.UTF_8) }
        } catch (failure: IOException) {
            Log.w("HomeDJOffline", "Song info save failed: videoId=" + track.id + " error=" + failure.javaClass.simpleName)
        }
    }

    fun read(context: Context, id: String): YouTubeTrack? {
        val file = infoFile(context, id)
        if (!file.isFile || file.length() > MAX_INFO_BYTES) return null
        return runCatching { SavedPlaylistCodec.parseTrack(JSONObject(file.readText(Charsets.UTF_8))) }
            .getOrNull()?.takeIf { it.id == id }
    }

    /** YouTube's public thumbnail, HTTPS only, no redirects, at most 1 MiB; best effort. */
    fun saveArt(context: Context, id: String) {
        val address = youTubeArtworkUrl(id) ?: return
        val target = artFile(context, id)
        if (target.isFile && target.length() > 0) return
        try {
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK ||
                    connection.contentType?.startsWith("image/") != true ||
                    connection.contentLengthLong > MAX_ART_BYTES
                ) throw IOException("Unexpected artwork response")
                writeAtomically(target) { part ->
                    connection.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(8_192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count == -1) break
                                total += count
                                if (total > MAX_ART_BYTES) throw IOException("Artwork too large")
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJOffline", "Artwork save failed: videoId=" + id + " error=" + failure.javaClass.simpleName)
        }
    }

    fun delete(context: Context, id: String) {
        infoFile(context, id).delete()
        artFile(context, id).delete()
    }

    private fun writeAtomically(target: File, write: (File) -> Unit) {
        val folder = target.parentFile ?: throw IOException("No folder")
        if (!folder.exists() && !folder.mkdirs()) throw IOException("Cannot create folder")
        val part = File(folder, target.name + ".part")
        try {
            write(part)
            if (!part.renameTo(target)) throw IOException("Rename failed")
        } finally {
            part.delete()
        }
    }
}

/** A playlist's download progress, derived from its songs' committed files and pending saves. */
internal enum class DownloadState { NONE, PARTIAL, DOWNLOADING, DONE }

internal fun downloadState(total: Int, saved: Int, saving: Int): DownloadState = when {
    total <= 0 -> DownloadState.NONE
    saving > 0 -> DownloadState.DOWNLOADING
    saved >= total -> DownloadState.DONE
    saved > 0 -> DownloadState.PARTIAL
    else -> DownloadState.NONE
}

/** "1 downloaded song" or "3 downloaded songs", for removal messages on both phones. */
internal fun songCount(downloaded: Int): String =
    if (downloaded == 1) "1 downloaded song" else "$downloaded downloaded songs"

/** Notice after queuing a list's saves; songs that already have a file are never queued again. */
internal fun queuedNotice(queued: Int, source: String): String = when (queued) {
    0 -> "All these songs are already downloaded."
    1 -> "Queued 1 song $source."
    else -> "Queued $queued songs $source."
}
