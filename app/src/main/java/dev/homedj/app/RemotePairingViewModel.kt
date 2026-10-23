package dev.homedj.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class RemotePairingUiState(
    val mode: HomeDjMode? = null,
    val ready: Boolean = false,
    val host: String = "",
    val port: String = RemoteChannel.PORT.toString(),
    val code: String = "",
    val serverCode: String = "",
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Screen drafts only. RemotePrefs owns credentials; RemoteControllerViewModel verifies and saves. */
class RemotePairingViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = RemotePrefs(application)
    private val modes = ModeStore(application)
    private val mutableState = MutableStateFlow(RemotePairingUiState())
    internal val state = mutableState.asStateFlow()
    private var scanVersion = 0

    init {
        viewModelScope.launch {
            modes.mode.collectLatest { mode ->
                scanVersion++
                mutableState.value = RemotePairingUiState(mode = mode)
                try {
                    val restored = withContext(Dispatchers.IO) {
                        RemotePairingUiState(mode = mode, ready = true,
                            host = prefs.savedHost, code = prefs.savedClientKey,
                            serverCode = if (mode == HomeDjMode.SERVER) prefs.serverKey() else "")
                    }
                    mutableState.value = restored
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failed("Pairing settings load", failure, "Could not read pairing settings. Reopen the app to retry.")
                }
            }
        }
    }

    internal fun editHost(value: String) {
        if (value.length <= 32) mutableState.update { it.copy(host = value, error = null) }
    }

    internal fun editPort(value: String) {
        if (value.length <= 8) mutableState.update { it.copy(port = value, error = null) }
    }

    internal fun editCode(value: String) {
        if (value.length <= 128) mutableState.update { it.copy(code = value, error = null) }
    }

    internal fun connect(onConnect: (String, String) -> Unit) {
        val current = mutableState.value
        if (!current.ready || current.scanning || current.mode != HomeDjMode.CONTROLLER) return
        val input = remotePairingInput(current.host, current.port, current.code)
        if (input == null) {
            mutableState.update { it.copy(error = "Enter Samsung's private Wi-Fi IP, port 47188, and pairing code.") }
            return
        }
        mutableState.update { it.copy(host = input.host, code = input.code, error = null) }
        onConnect(input.host, input.code)
    }

    internal fun beginScan(): Int? {
        val current = mutableState.value
        if (!current.ready || current.scanning || current.mode != HomeDjMode.CONTROLLER) return null
        mutableState.update { it.copy(scanning = true, error = null) }
        return ++scanVersion
    }

    internal fun scanned(version: Int, payload: String?) {
        if (version != scanVersion || !mutableState.value.scanning) return
        val input = parseRemotePairingQr(payload)
        mutableState.update {
            if (input == null) it.copy(scanning = false, error = "Scan the HomeDJ pairing QR in Samsung Settings.")
            else it.copy(host = input.host, port = RemoteChannel.PORT.toString(), code = input.code,
                scanning = false, error = null)
        }
    }

    internal fun scanCancelled(version: Int) {
        if (version == scanVersion) mutableState.update { it.copy(scanning = false) }
    }

    internal fun scanFailed(version: Int, failure: Exception) {
        if (version != scanVersion) return
        failed("Pairing QR scan", failure,
            "Scanner unavailable. Check Google Play services and internet, or enter the details manually.")
    }

    internal fun replaceServerCode() {
        if (mutableState.value.busy || !mutableState.value.ready) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val code = withContext(Dispatchers.IO) {
                    check(modes.mode.first() == HomeDjMode.SERVER)
                    check(!RemoteServerService.running.value && !prefs.enabled)
                    prefs.replaceServerKey()
                }
                if (mutableState.value.mode == HomeDjMode.SERVER) {
                    mutableState.update { it.copy(serverCode = code, busy = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed("Pairing code replacement", failure, "Turn off remote control before creating a shorter code.")
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun failed(operation: String, failure: Exception, message: String) {
        Log.w("HomeDJRemote", "$operation failed: " + failure.javaClass.simpleName)
        mutableState.update { it.copy(scanning = false, error = message) }
    }
}
