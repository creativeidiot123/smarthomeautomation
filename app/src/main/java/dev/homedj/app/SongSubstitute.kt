package dev.homedj.app

import java.util.Locale
import kotlin.math.abs

/**
 * YouTube refuses some songs to the clients NewPipe uses, typically YouTube Music "Topic" tracks
 * ("This video is not available"), while the same song is also a regular upload. A download then
 * searches regular YouTube for the song's title and artist and saves the best matching upload's
 * audio under the original video ID, so playlists, queues and the Downloaded view play it unchanged.
 *
 * A match must contain every word of the song's title, run within [toleranceSeconds] of its length
 * and not be another version (cover, live, remix...) unless the song itself is one. Songs with no
 * known length are never substituted.
 */
internal object SongSubstitute {
    /** Matching uploads tried per refused song before it fails as before. */
    const val MAX_TRIES = 3
    private val versionWords = setOf("cover", "karaoke", "instrumental", "live", "remix", "slowed", "reverb",
        "sped", "nightcore", "8d", "reaction", "lofi", "mashup", "acoustic", "piano", "tutorial", "lesson", "shorts")

    internal fun toleranceSeconds(duration: Int): Int = maxOf(10, duration * 6 / 100)

    internal fun words(text: String): List<String> =
        text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** "Animals (Official Audio)" -> "Animals": bracketed parts and "feat." tails do not name the song. */
    internal fun cleanTitle(title: String): String =
        title.replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .split(Regex("""\s(?:feat\.?|ft\.?)\s""", RegexOption.IGNORE_CASE)).first().trim()

    private fun artist(track: YouTubeTrack) = track.artist.removeSuffix(" - Topic").trim()

    fun query(track: YouTubeTrack): String = (cleanTitle(track.title) + " " + artist(track)).trim()

    /** Acceptable uploads, best first: artist named, then closest length, then YouTube's order. */
    fun pick(track: YouTubeTrack, hits: List<SearchHit>): List<SearchHit> {
        if (track.durationSeconds <= 0) return emptyList()
        val titleWords = words(cleanTitle(track.title)).ifEmpty { return emptyList() }
        val songWords = words(track.title).toSet()
        val artistWords = words(artist(track)).toSet()
        val tolerance = toleranceSeconds(track.durationSeconds)
        return hits.withIndex().filter { (_, hit) ->
            val hitWords = words(hit.title)
            hit.id != track.id && NativeAudioSource.validId(hit.id) && !hit.uploader.endsWith(" - Topic") &&
                hit.durationSeconds > 0 && abs(hit.durationSeconds - track.durationSeconds) <= tolerance &&
                hitWords.containsAll(titleWords) && versionWords.none { it in hitWords && it !in songWords }
        }.sortedWith(
            compareByDescending<IndexedValue<SearchHit>> { (_, hit) ->
                artistWords.isNotEmpty() && words(hit.title + " " + hit.uploader).any { it in artistWords }
            }.thenBy { abs(it.value.durationSeconds - track.durationSeconds) }.thenBy { it.index }
        ).map { it.value }.take(MAX_TRIES)
    }

    /**
     * Resolves [track]'s own streams; when YouTube refuses it (not a bot check, which an upload of
     * the same song from the same address would meet too), the first matching upload that resolves.
     * The choice sticks for the rest of the attempt, so a link refresh re-resolves the same upload.
     */
    fun resolver(
        track: YouTubeTrack,
        resolve: suspend (String) -> List<NativeAudioSource.Link> = NativeAudioSource::resolveAll,
        search: suspend (String) -> List<SearchHit> = NativeAudioSource::searchVideos,
        onSubstitute: (String) -> Unit = {},
    ): suspend (String) -> List<NativeAudioSource.Link> {
        var chosen: String? = null
        return resolver@{ id ->
            chosen?.let { return@resolver resolve(it) }
            try {
                resolve(id)
            } catch (refused: TrackUnavailableException) {
                if (refused is BotCheckException) throw refused
                for (hit in pick(track, search(query(track)))) {
                    val links = try {
                        resolve(hit.id)
                    } catch (other: TrackUnavailableException) {
                        if (other is BotCheckException) throw other
                        continue
                    }
                    chosen = hit.id
                    onSubstitute(hit.id)
                    return@resolver links
                }
                throw refused
            }
        }
    }
}
