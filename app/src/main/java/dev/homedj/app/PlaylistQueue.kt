package dev.homedj.app

import kotlin.random.Random

/** A playlist queue that always holds the tapped song; [start] is its index in [tracks]. */
internal data class PlaylistQueue(val tracks: List<YouTubeTrack>, val start: Int)

/**
 * Valid, distinct songs of a playlist, at most [limit], windowed so the tapped song is included.
 * Null when [startId] is not in the playlist, so a caller never queues a song the playlist lacks.
 */
internal fun playlistQueue(tracks: List<YouTubeTrack>, startId: String, limit: Int = 500): PlaylistQueue? {
    val songs = tracks.filter { NativeAudioSource.validId(it.id) }.distinctBy(YouTubeTrack::id)
    val index = songs.indexOfFirst { it.id == startId }
    if (index < 0) return null
    val from = (index - limit + 1).coerceAtLeast(0)
    return PlaylistQueue(songs.subList(from, minOf(songs.size, from + limit)), index - from)
}

/** A shuffled play order beginning at [start], so the tapped song plays first and every other song follows. */
internal fun shuffleOrderStartingAt(size: Int, start: Int, random: Random): IntArray =
    (listOf(start) + (0 until size).filter { it != start }.shuffled(random)).toIntArray()
