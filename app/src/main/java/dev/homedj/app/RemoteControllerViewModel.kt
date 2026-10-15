package dev.homedj.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class RemoteUiState(
    val host: String = "",
    val pairingCode: String = "",
    val connected: Boolean = false,
    val title: String = "No server connected",
    val playing: Boolean = false,
    val hasTrack: Boolean = false,
    val queueSize: Int = 0,
    val shuffle: Boolean = false,
    val volume: Int = 0,
    val error: String? = null,
)

/** Controller projects the remote server's state. It never owns a local playback engine. */
class RemoteControllerViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = RemotePrefs(application)
    private val mutableState = MutableStateFlow(
        RemoteUiState(host = prefs.savedHost, pairingCode = prefs.savedClientKey)
    )
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var connection: Job? = null

    fun start() {
        val current = mutableState.value
        if (current.host.isNotBlank() && current.pairingCode.isNotBlank()) {
            connect(current.host, current.pairingCode)
        }
    }

    fun connect(host: String, code: String) {
        val address = host.trim()
        val key = RemoteChannel.decodeKey(code)
        if (!RemotePrefs.validPrivateIpv4(address) || key == null) {
            mutableState.value = mutableState.value.copy(
                connected = false, error = "Enter the server's private Wi-Fi IPv4 and pairing code."
            )
            return
        }
        connection?.cancel()
        mutableState.value = RemoteUiState(host = address, pairingCode = code.trim())
        connection = viewModelScope.launch {
            while (isActive) {
                try {
                    val response = exchange(address, key, "status")
                    if (!response.optBoolean("ok")) {
                        throw IllegalStateException(response.optString("error", "Server rejected request"))
                    }
                    prefs.saveClient(address, code)
                    show(response)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    Log.w("HomeDJRemote", "Remote status failed: " + failure.javaClass.simpleName)
                    mutableState.value = mutableState.value.copy(
                        connected = false, error = "Server unavailable or pairing code incorrect."
                    )
                }
                delay(2_500)
            }
        }
    }

    fun command(action: String, percent: Int? = null) {
        val state = mutableState.value
        val key = RemoteChannel.decodeKey(state.pairingCode) ?: return
        if (!state.connected) return
        viewModelScope.launch {
            try {
                val response = exchange(state.host, key, action, percent)
                if (response.optBoolean("ok")) show(response)
                else mutableState.value = mutableState.value.copy(
                    error = response.optString("error", "Remote command rejected")
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Log.w("HomeDJRemote", "Remote command failed: " + failure.javaClass.simpleName)
                mutableState.value = mutableState.value.copy(
                    connected = false, error = "Command failed. Check Wi-Fi connection."
                )
            }
        }
    }

    private suspend fun exchange(
        host: String, key: SecretKeySpec, command: String, percent: Int? = null,
    ): JSONObject = mutex.withLock {
        withContext(Dispatchers.IO) {
            val request = JSONObject().put("command", command)
            if (percent != null) request.put("percent", percent)
            RemoteChannel.exchange(host, key, request)
        }
    }

    private fun show(response: JSONObject) {
        mutableState.value = mutableState.value.copy(
            connected = true,
            title = response.optString("title", "No track selected"),
            playing = response.optBoolean("playing"),
            hasTrack = response.optBoolean("hasTrack"),
            queueSize = response.optInt("queueSize"),
            shuffle = response.optBoolean("shuffle"),
            volume = response.optInt("volume"),
            error = null,
        )
    }

    fun stop() {
        connection?.cancel()
        connection = null
        mutableState.value = mutableState.value.copy(connected = false)
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
