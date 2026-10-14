package dev.homedj.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class RoleUiState(
    val loaded: Boolean = false,
    val mode: HomeDjMode? = null,
    val changing: Boolean = false,
    val error: String? = null,
)

/** Device-local preference projection; neither screen owns the selected role. */
class RoleViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ModeStore(application)
    private val mutableState = MutableStateFlow(RoleUiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            store.mode.catch { exception ->
                Log.e("HomeDJ", "Unable to load saved device role", exception)
                mutableState.value = RoleUiState(loaded = false, error = "Unable to read device settings")
            }.collect { role ->
                mutableState.value = mutableState.value.copy(loaded = true, mode = role, error = null)
            }
        }
    }

    fun select(mode: HomeDjMode) {
        if (!mutableState.value.loaded || mutableState.value.changing) return
        mutableState.value = mutableState.value.copy(changing = true, error = null)
        viewModelScope.launch {
            try {
                store.select(mode)
            } catch (exception: Exception) {
                if (exception is kotlinx.coroutines.CancellationException) throw exception
                Log.e("HomeDJ", "Unable to save selected role", exception)
                mutableState.value = mutableState.value.copy(error = "Unable to save device settings")
            } finally {
                mutableState.value = mutableState.value.copy(changing = false)
            }
        }
    }
}
