package dev.homedj.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

internal fun parsePlaylistSort(stored: String?): PlaylistSort =
    PlaylistSort.entries.firstOrNull { it.name == stored } ?: PlaylistSort.ORIGINAL

/** Sort key of each phone's Downloaded page; the colon keeps it apart from YouTube playlist IDs. */
internal const val DOWNLOADED_SORT_KEY = ":downloaded"

/**
 * Per-playlist view preference; reuses the one existing settings DataStore. Each playlist's choice
 * lives under `playlist_sort:<playlist>`; playlist order is the default and stores nothing.
 */
internal class PlaylistSortStore(private val preferences: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.homeDjStore)

    suspend fun read(): Map<String, PlaylistSort> = preferences.data.first().asMap().mapNotNull { (key, value) ->
        val playlist = key.name.removePrefix(PREFIX).takeIf { key.name.startsWith(PREFIX) && it.isNotEmpty() }
        val sort = parsePlaylistSort(value as? String)
        if (playlist == null || sort == PlaylistSort.ORIGINAL) null else playlist to sort
    }.toMap()

    suspend fun select(playlist: String, sort: PlaylistSort) {
        val key = stringPreferencesKey(PREFIX + playlist)
        preferences.edit { if (sort == PlaylistSort.ORIGINAL) it.remove(key) else it[key] = sort.name }
    }

    private companion object {
        const val PREFIX = "playlist_sort:"
    }
}
