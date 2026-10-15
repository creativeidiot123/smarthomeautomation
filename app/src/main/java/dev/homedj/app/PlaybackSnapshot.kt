package dev.homedj.app

import java.util.Locale

/** A read-only view of the server-owned Media3 player. Not a second playback state owner. */
data class PlaybackSnapshot(
    val title: String = "No track selected",
    val isPlaying: Boolean = false,
    val hasTrack: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val queueSize: Int = 0,
    val shuffle: Boolean = false,
    /** Thumbnail of the current song from its Media3 metadata; null for local files. */
    val artworkUrl: String? = null,
    val artist: String = "",
    /** Where the song was at [positionAt] (`SystemClock.elapsedRealtime`); the UI extrapolates while playing. */
    val positionMs: Long = 0L,
    /** Song length, or -1 while unknown (not loaded yet, live audio). */
    val durationMs: Long = -1L,
    val positionAt: Long = 0L,
)

/** Where a playing song is now, from a snapshot taken at [at]; never past its end or before 0. */
internal fun displayedPosition(positionMs: Long, at: Long, now: Long, playing: Boolean, durationMs: Long): Long {
    val moved = if (playing) (now - at).coerceAtLeast(0) else 0
    val position = (positionMs + moved).coerceAtLeast(0)
    return if (durationMs > 0) position.coerceAtMost(durationMs) else position
}

/** `m:ss`, or `h:mm:ss` for an hour or more. */
internal fun formatDuration(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    val rest = seconds % 60
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
        else String.format(Locale.ROOT, "%d:%02d", minutes, rest)
}

/** A song's second line: "Artist • 3:05", either part when known. */
internal fun songDetails(artist: String, durationSeconds: Int): String =
    listOfNotNull(artist.takeIf { it.isNotBlank() },
        durationSeconds.takeIf { it > 0 }?.let { formatDuration(it.toLong()) }).joinToString(" • ")
