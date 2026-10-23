package dev.homedj.app

import org.json.JSONObject

/** Parses a song row of a LAN `tracks` or `downloads` reply; detail fields (and `failed`) are optional. */
internal fun remoteSong(item: JSONObject): RemoteSong =
    RemoteSong(item.optString("id"), item.optString("title"), item.optBoolean("saved"), item.optBoolean("saving"),
        item.optString("artist").take(200), item.optInt("duration").coerceIn(0, MAX_TRACK_SECONDS),
        item.optBoolean("failed"))

/**
 * What Samsung just confirmed, shown before the next refresh: songs accepted for saving show
 * "Saving…", cancelled ones stop, removed ones are no longer saved.
 */
internal fun RemoteUiState.withSavesQueued(action: String, id: String, kind: String? = null): RemoteUiState {
    val wholePlaylist = browserMode == "tracks" && selectedPlaylistId == id &&
        (action == "savePlaylist" || kind == "playlist")
    val touches: (RemoteSong) -> Boolean = { wholePlaylist || it.id == id }
    val song = songs.firstOrNull { it.id == id }
    return when {
        action == "saveTrack" -> copy(
            songs = songs.map { if (it.id == id && !it.saved) it.copy(saving = true) else it },
            savingCount = savingCount + if (song != null && !song.saved && !song.saving) 1 else 0,
        )
        action == "savePlaylist" && wholePlaylist -> copy(
            songs = songs.map { if (it.saved) it else it.copy(saving = true) },
            savingCount = if (savedCount >= 0) browserTotal - savedCount else savingCount,
        )
        action == "cancelSave" -> copy(
            songs = songs.map { if (touches(it)) it.copy(saving = false) else it },
            savingCount = if (wholePlaylist) 0
                else savingCount - if (song?.saving == true) 1 else 0,
        )
        action == "removeSaved" && browserMode == "downloads" -> songs.filterNot(touches).let { left ->
            copy(songs = left, browserTotal = (browserTotal - (songs.size - left.size)).coerceAtLeast(0))
        }
        action == "removeSaved" -> copy(
            songs = songs.map { if (touches(it)) it.copy(saved = false, saving = false) else it },
            savedCount = if (wholePlaylist) 0 else savedCount - if (song?.saved == true) 1 else 0,
            savingCount = if (wholePlaylist) 0 else savingCount - if (song?.saving == true) 1 else 0,
        )
        else -> this
    }
}

/**
 * Loaded rows a status poll re-reads: all of them (at least Downloaded's first page) once Samsung's
 * downloads changed on either phone, else only the span of playlist rows still saving; null for none.
 */
internal fun RemoteUiState.songsToRefresh(
    downloadsChanged: Boolean = downloadsRevision != null && songsDownloadsRevision != downloadsRevision,
): IntRange? = when {
    browserLoading -> null
    browserMode == "downloads" -> if (downloadsChanged) 0..songs.lastIndex.coerceAtLeast(0) else null
    browserMode != "tracks" || selectedPlaylistId == null -> null
    downloadsChanged -> songs.indices.takeUnless { it.isEmpty() }
    else -> songs.indexOfFirst { it.saving }.takeIf { it >= 0 }?.let { it..songs.indexOfLast(RemoteSong::saving) }
}

/** Downloaded takes Samsung's fresh rows in their new order; a playlist keeps its rows and updates them. */
internal fun RemoteUiState.withFreshSongs(fresh: List<RemoteSong>, total: Int, counts: Pair<Int, Int>?): RemoteUiState =
    if (browserMode == "downloads") copy(songs = fresh, browserTotal = total)
    else fresh.associateBy(RemoteSong::id).let { byId ->
        copy(songs = songs.map { byId[it.id] ?: it }, savedCount = counts?.first ?: savedCount,
            savingCount = counts?.second ?: savingCount)
    }

/** A Samsung build without download management answers "Unknown command". */
internal fun downloadChangeError(action: String, error: String): String =
    if (action in setOf("cancelSave", "removeSaved") && error == "Unknown command")
        "Update HomeDJ on Samsung to manage downloads." else error

/** A Samsung build without playlist refresh answers "Unknown command". */
internal fun refreshPlaylistError(error: String): String = when (error) {
    "Unknown command", "Unknown library command" -> "Update HomeDJ on Samsung to refresh playlists."
    "" -> "Samsung could not refresh this playlist."
    else -> error
}
