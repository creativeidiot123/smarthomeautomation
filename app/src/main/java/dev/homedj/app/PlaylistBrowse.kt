package dev.homedj.app

/** Existing snapshots stay usable until explicit/hourly refresh. Never cross account identities. */
internal fun cachedPlaylistTracks(
    catalog: SavedPlaylistCatalog, id: String, accountName: String?,
): List<YouTubeTrack>? {
    if (accountName != null && catalog.ownerKey != SavedPlaylistStore.accountKey(accountName)) return null
    return catalog.tracks[id]
}

/** Preserve repeated songs too; LazyColumn requires a unique, restorable key for each occurrence. */
internal fun keyedPlaylistSongs(songs: List<PageSong>): List<Pair<String, PageSong>> {
    val occurrences = HashMap<String, Int>()
    return songs.map { song ->
        val occurrence = occurrences[song.id] ?: 0
        occurrences[song.id] = occurrence + 1
        "song:${song.id}:$occurrence" to song
    }
}
