package dev.homedj.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PlaylistSortUiState(
    val sorts: Map<String, PlaylistSort> = emptyMap(),
    val loaded: Boolean = false,
    val error: String? = null,
) {
    fun sortFor(playlist: String): PlaylistSort = sorts[playlist] ?: PlaylistSort.ORIGINAL
}

/** One Activity-owned projection, retained across tabs and recreation; DataStore owns each playlist's choice. */
internal class PlaylistSortViewModel(application: Application) : AndroidViewModel(application) {
    private val store = PlaylistSortStore(application)
    private val writes = Mutex()
    private val mutableState = MutableStateFlow(PlaylistSortUiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                mutableState.value = PlaylistSortUiState(sorts = store.read(), loaded = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeDJ", "Read playlist sort failed: " + failure.javaClass.simpleName)
                mutableState.value = PlaylistSortUiState(loaded = true, error = "Could not read saved sorting preference.")
            }
        }
    }

    // Queue rapid choices in tap order; publish only after the durable edit succeeds.
    fun select(playlist: String, sort: PlaylistSort) {
        if (!mutableState.value.loaded) return
        viewModelScope.launch {
            writes.withLock {
                try {
                    store.select(playlist, sort)
                    mutableState.update {
                        val sorts = if (sort == PlaylistSort.ORIGINAL) it.sorts - playlist else it.sorts + (playlist to sort)
                        it.copy(sorts = sorts, error = null)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("HomeDJ", "Save playlist sort failed: " + failure.javaClass.simpleName)
                    mutableState.update { it.copy(error = "Could not save sorting preference. Try again.") }
                }
            }
        }
    }
}
