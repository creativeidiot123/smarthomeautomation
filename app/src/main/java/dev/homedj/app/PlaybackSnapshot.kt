package dev.homedj.app

/** A read-only view of the server-owned Media3 player. Not a second playback state owner. */
data class PlaybackSnapshot(
    val title: String = "No track selected",
    val isPlaying: Boolean = false,
    val hasTrack: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val queueSize: Int = 0,
    val shuffle: Boolean = false,
) {
    val canPlay: Boolean get() = hasTrack && !isPlaying
    val canPause: Boolean get() = hasTrack && isPlaying
}
