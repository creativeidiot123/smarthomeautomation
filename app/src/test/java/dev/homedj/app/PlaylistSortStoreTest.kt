package dev.homedj.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaylistSortStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private suspend fun <T> withSettings(file: File, action: suspend (PlaylistSortStore) -> T): T {
        val lifetime = SupervisorJob()
        val preferences = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(lifetime + Dispatchers.IO), produceFile = { file })
        return try {
            action(PlaylistSortStore(preferences))
        } finally {
            lifetime.cancelAndJoin()
        }
    }

    @Test fun everySortingChoiceSurvivesClosingAndReopeningTheSettingsFile() = runBlocking {
        val file = File(temporary.root, "settings.preferences_pb")
        for (sort in PlaylistSort.entries) {
            withSettings(file) { it.select("PLabc", sort) }
            withSettings(file) { assertEquals(sort, PlaylistSortUiState(it.read()).sortFor("PLabc")) }
        }
    }

    @Test fun eachPlaylistKeepsItsOwnChoice() = runBlocking {
        val file = File(temporary.root, "settings.preferences_pb")
        withSettings(file) { store ->
            store.select("PLfirst", PlaylistSort.TITLE_ASC)
            store.select("PLsecond", PlaylistSort.LONGEST)
            store.select(DOWNLOADED_SORT_KEY, PlaylistSort.ARTIST_DESC)
        }
        withSettings(file) { store ->
            val state = PlaylistSortUiState(store.read())
            assertEquals(PlaylistSort.TITLE_ASC, state.sortFor("PLfirst"))
            assertEquals(PlaylistSort.LONGEST, state.sortFor("PLsecond"))
            assertEquals(PlaylistSort.ARTIST_DESC, state.sortFor(DOWNLOADED_SORT_KEY))
            assertEquals(PlaylistSort.ORIGINAL, state.sortFor("PLnever"))
            store.select("PLfirst", PlaylistSort.ORIGINAL)
            assertEquals(mapOf("PLsecond" to PlaylistSort.LONGEST, DOWNLOADED_SORT_KEY to PlaylistSort.ARTIST_DESC),
                store.read())
        }
    }

    @Test fun existingDeviceSettingsOldDeviceWideSortAndUnknownValuesRemainReadable() = runBlocking {
        val file = File(temporary.root, "settings.preferences_pb")
        val lifetime = SupervisorJob()
        val preferences = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(lifetime + Dispatchers.IO), produceFile = { file })
        try {
            val role = stringPreferencesKey("device_role")
            val legacy = stringPreferencesKey("playlist_sort")
            val sort = stringPreferencesKey("playlist_sort:PLabc")
            preferences.edit { it[role] = "SERVER"; it[legacy] = "TITLE_ASC" }
            val store = PlaylistSortStore(preferences)
            assertEquals(emptyMap<String, PlaylistSort>(), store.read())
            preferences.edit { it[sort] = "FUTURE_SORT" }
            assertEquals(emptyMap<String, PlaylistSort>(), store.read())
            store.select("PLabc", PlaylistSort.ARTIST_DESC)
            assertEquals("ARTIST_DESC", preferences.data.first()[sort])
            assertEquals("SERVER", preferences.data.first()[role])
        } finally {
            lifetime.cancelAndJoin()
        }
        withSettings(file) { assertEquals(mapOf("PLabc" to PlaylistSort.ARTIST_DESC), it.read()) }
    }

    @Test fun repeatedChoicesAndSequentialEditsKeepTheLastChoice() = runBlocking {
        val file = File(temporary.root, "settings.preferences_pb")
        withSettings(file) { store ->
            val commands = launch {
                repeat(3) { store.select("PLabc", PlaylistSort.SHORTEST) }
                store.select("PLabc", PlaylistSort.LONGEST)
                store.select("PLabc", PlaylistSort.TITLE_ASC)
            }
            commands.join()
            assertEquals(mapOf("PLabc" to PlaylistSort.TITLE_ASC), store.read())
        }
        withSettings(file) { assertEquals(mapOf("PLabc" to PlaylistSort.TITLE_ASC), it.read()) }
    }

    @Test fun oldAndInvalidStoredValuesUsePlaylistOrder() {
        listOf(null, "", "invalid", "title_asc").forEach {
            assertEquals(PlaylistSort.ORIGINAL, parsePlaylistSort(it))
        }
        assertEquals(
            listOf("ORIGINAL", "REVERSE", "TITLE_ASC", "TITLE_DESC", "ARTIST_ASC", "ARTIST_DESC",
                "SHORTEST", "LONGEST", "DOWNLOADED"), PlaylistSort.entries.map { it.name })
    }
}
