package dev.homedj.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class HomeDjMode { SERVER, CONTROLLER }

internal fun parseHomeDjMode(stored: String?): HomeDjMode? =
    HomeDjMode.entries.firstOrNull { it.name == stored }

private val Context.homeDjStore by preferencesDataStore(name = "homedj_settings")

/** The selected role is device-local. Preferences DataStore is its only persistent owner. */
class ModeStore(context: Context) {
    private val preferences = context.applicationContext.homeDjStore
    val mode: Flow<HomeDjMode?> =
        preferences.data.map { parseHomeDjMode(it[MODE_KEY]) }

    suspend fun select(mode: HomeDjMode) {
        preferences.edit { it[MODE_KEY] = mode.name }
    }

    private companion object {
        val MODE_KEY = stringPreferencesKey("device_role")
    }
}
