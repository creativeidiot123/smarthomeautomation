package dev.homedj.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ScheduleUiState(
    val slots: List<ScheduleSlot> = emptyList(),
    /** Cached song count per playlist ID; a slot can only play songs Samsung has cached. */
    val cachedSongs: Map<String, Int> = emptyMap(),
    val saving: Boolean = false,
    val error: String? = null,
    val exactAlarmsAllowed: Boolean = true,
    /** Bumped on every committed edit so the editor knows it may close. */
    val revision: Int = 0,
)

/** Samsung's schedule editor. [ScheduleStore] is the truth; PlaybackService applies it. */
internal class ScheduleViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ScheduleStore.shared(application)
    private val catalog = SavedPlaylistStore.shared(application)
    private val mutableState = MutableStateFlow(ScheduleUiState())
    val state = mutableState.asStateFlow()
    private var saveJob: Job? = null

    init { load() }

    fun load() {
        refreshPermission()
        viewModelScope.launch {
            try {
                val slots = store.read()
                val cached = catalog.read().tracks.mapValues { it.value.size }
                mutableState.value = mutableState.value.copy(slots = slots, cachedSongs = cached)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeDJ", "Schedule load failed: " + failure.javaClass.simpleName)
                mutableState.value = mutableState.value.copy(error = "Could not load the schedule")
            }
        }
    }

    fun refreshPermission() {
        mutableState.value = mutableState.value.copy(
            exactAlarmsAllowed = ScheduleAlarms.canSchedule(getApplication()))
    }

    /** A null [id] adds a new slot. */
    fun save(id: String?, startHour: Int, endHour: Int, volume: Int, playlistIds: List<String>) {
        val slot = ScheduleSlot(id ?: UUID.randomUUID().toString(), startHour, endHour, volume, playlistIds.distinct())
        mutate { slots -> slots.filterNot { it.id == slot.id } + slot }
    }

    fun delete(id: String) = mutate { slots -> slots.filterNot { it.id == id } }

    /** First-wins: a second tap while a write is in flight is ignored. */
    private fun mutate(change: (List<ScheduleSlot>) -> List<ScheduleSlot>) {
        if (saveJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        saveJob = viewModelScope.launch {
            try {
                val slots = store.mutate(change)
                mutableState.value = mutableState.value.copy(
                    slots = slots, saving = false, revision = mutableState.value.revision + 1)
                RemoteActivity.record(RemoteActivity.SAMSUNG, "Schedule updated")
                // PlaybackService re-arms its alarm and starts or pauses to match the new schedule.
                ScheduleAlarms.tickNow(getApplication())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: IllegalArgumentException) {
                mutableState.value = mutableState.value.copy(saving = false, error = invalid.message)
            } catch (failure: Exception) {
                Log.w("HomeDJ", "Schedule save failed: " + failure.javaClass.simpleName)
                mutableState.value = mutableState.value.copy(saving = false, error = "Could not save the schedule")
            }
        }
    }
}
