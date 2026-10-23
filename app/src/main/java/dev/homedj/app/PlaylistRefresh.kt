package dev.homedj.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What a playlist refresh did; only [Done] committed anything. */
internal sealed interface RefreshOutcome {
    /** Not in the snapshot any more, or the snapshot changed owner (disconnect, account switch). */
    data object Unknown : RefreshOutcome
    /** YouTube says the playlist is private or gone; the saved songs stay as they were. */
    data object Unavailable : RefreshOutcome
    /** Samsung left the Server role while the playlist was read. */
    data object Inactive : RefreshOutcome
    /** [added] songs are new since the last read; [queued] of them started downloading now. */
    data class Done(val title: String, val changed: Boolean, val added: Int, val queued: Int) : RefreshOutcome
}

/**
 * Re-reads a saved playlist's songs from YouTube, for the hourly sync and Refresh on either phone,
 * and for Samsung opening a playlist while signed in. The snapshot keeps the new list; songs
 * removed upstream leave it but their downloaded audio stays. Songs added to a playlist that
 * already has a downloaded or downloading song are queued as Wi-Fi-only saves.
 */
internal object PlaylistRefresh {
    const val MAX_PLAYLISTS_PER_PASS = 30
    const val MAX_QUEUED_PER_PASS = 150
    private const val TAG = "HomeDJAuto"

    /** Reads with [token] (the snapshot's own account) when given, else, or when the API refuses, as a link. */
    suspend fun refresh(app: Context, token: String?, id: String, budget: Int = MAX_QUEUED_PER_PASS): RefreshOutcome {
        val ownerKey = SavedPlaylistStore.shared(app).read().ownerKey
        if (token != null) {
            try {
                return commit(app, ownerKey, id, YouTubePlaylistRepository().tracks(token, id), null, budget)
            } catch (refused: YouTubeHttpFailure) {
                Log.w(TAG, "Playlist API read refused: status=" + refused.statusCode + "; reading as a link")
            }
        }
        val linked = NativeAudioSource.playlist(id) ?: return RefreshOutcome.Unavailable
        return commit(app, ownerKey, id, linked.tracks, linked.playlist, budget)
    }

    /**
     * Stores songs just read for [id] in the snapshot of [ownerKey], then queues additions. The
     * baseline is read just before the write; two refreshes at once queue the same songs, which
     * unique save work keeps to one download each.
     */
    suspend fun commit(
        app: Context, ownerKey: String?, id: String, read: List<YouTubeTrack>, details: YouTubePlaylist?,
        budget: Int = MAX_QUEUED_PER_PASS,
    ): RefreshOutcome {
        if (ModeStore(app).mode.first() != HomeDjMode.SERVER) return RefreshOutcome.Inactive
        val store = SavedPlaylistStore.shared(app)
        val before = store.read()
        val playlist = before.playlists.firstOrNull { it.id == id }
        if (playlist == null || before.ownerKey != ownerKey) return RefreshOutcome.Unknown
        val fresh = read.filter { NativeAudioSource.validId(it.id) }.distinctBy(YouTubeTrack::id)
        val previous = before.tracks[id].orEmpty()
        val changed = fresh != previous || (details != null && details != playlist)
        if (changed && !store.refreshTracks(ownerKey, id, fresh, details)) return RefreshOutcome.Unknown
        val pending = OfflineAudioLibrary.pendingIds(app).first()
        val save = withContext(Dispatchers.IO) {
            additions(previous, fresh) { song -> song in pending || OfflineAudioLibrary.existing(app, song) != null }
        }
        val queued = withContext(Dispatchers.IO) {
            save.take(budget.coerceAtLeast(0)).count { OfflineAudioLibrary.enqueue(app, it, playlistId = id) }
        }
        return RefreshOutcome.Done(details?.title ?: playlist.title, changed, newSongs(previous, fresh).size, queued)
    }

    /** Songs in [fresh] that [previous] did not list. A first read has no baseline, so nothing is new. */
    internal fun newSongs(previous: List<YouTubeTrack>, fresh: List<YouTubeTrack>): List<YouTubeTrack> {
        if (previous.isEmpty()) return emptyList()
        val known = previous.mapTo(hashSetOf(), YouTubeTrack::id)
        return fresh.filter { it.id !in known }
    }

    /** New songs to download: only when a song [previous] listed is [kept] (downloaded or downloading). */
    internal fun additions(
        previous: List<YouTubeTrack>, fresh: List<YouTubeTrack>, kept: (String) -> Boolean,
    ): List<YouTubeTrack> = if (previous.none { kept(it.id) }) emptyList() else newSongs(previous, fresh)
}

/** The reply and notice for a refresh, the same on both phones. */
internal fun refreshMessage(outcome: RefreshOutcome.Done): String = when {
    outcome.added == 0 -> if (outcome.changed) "Refreshed ${outcome.title}." else "${outcome.title} is up to date."
    outcome.queued == 0 -> "Found ${newSongCount(outcome.added)} in ${outcome.title}."
    else -> "Found ${newSongCount(outcome.added)} in ${outcome.title}. Downloading ${outcome.queued} on Wi-Fi."
}

private fun newSongCount(count: Int): String = if (count == 1) "1 new song" else "$count new songs"

/** Fixed reasons for a refresh that committed nothing; Samsung sends them as the reply's error. */
internal fun refreshError(outcome: RefreshOutcome): String = when (outcome) {
    RefreshOutcome.Unavailable -> "This playlist is private or no longer on YouTube."
    RefreshOutcome.Inactive -> "Server role inactive"
    else -> "Unknown playlist"
}
