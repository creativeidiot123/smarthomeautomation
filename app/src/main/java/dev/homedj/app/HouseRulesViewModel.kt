package dev.homedj.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class HouseRulesUiState(
    val mode: HomeDjMode? = null,
    /** Samsung's saved text; the editor's draft lives in the screen until Save. */
    val script: String = "",
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val saved: Boolean = false,
    val message: String? = null,
)

/**
 * House rules on both phones. Samsung calls [HouseRules] directly; the OnePlus sends the LAN
 * `houseRules`/`houseRulesSave` commands and shows the text Samsung replies with. Saving is
 * first-wins; Samsung validates the text and its names again.
 */
class HouseRulesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val modeStore = ModeStore(application)
    private val prefs = RemotePrefs(application)
    private val mutableState = MutableStateFlow(HouseRulesUiState())
    internal val state: StateFlow<HouseRulesUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            modeStore.mode.distinctUntilChanged().collectLatest { mode ->
                job?.cancel()
                mutableState.value = HouseRulesUiState(mode = mode)
                if (mode == HomeDjMode.SERVER) HouseRules.state(app).collect { script ->
                    mutableState.update { it.copy(script = script, loaded = true) }
                }
            }
        }
    }

    /** OnePlus: fetches Samsung's rules. Samsung already shows its own. */
    fun refresh() = run { mode -> if (mode == HomeDjMode.CONTROLLER) remote(JSONObject().put("command", "houseRules")) }

    fun save(script: String) = run { mode ->
        if (mode == HomeDjMode.SERVER) {
            val outcome = HouseRules.save(app, script)
            mutableState.update { it.copy(saved = outcome.ok, message = outcome.message) }
        } else {
            remote(JSONObject().put("command", "houseRulesSave").put("script", script))
        }
    }

    private suspend fun remote(request: JSONObject) {
        val host = prefs.savedHost
        val key = RemoteChannel.decodeKey(prefs.savedClientKey)
        if (host.isBlank() || key == null) {
            return mutableState.update { it.copy(message = "Connect to the Samsung in Settings first.") }
        }
        val reply = withContext(Dispatchers.IO) { RemoteChannel.exchange(host, key, request, replyTimeoutMillis = REPLY_MILLIS) }
        val unknown = reply.optString("error") == "Unknown command"
        mutableState.update {
            it.copy(
                script = if (reply.has("script")) reply.optString("script") else it.script,
                loaded = it.loaded || reply.has("script"),
                saved = reply.optBoolean("ok") && request.optString("command") == "houseRulesSave",
                message = when {
                    unknown -> "Update HomeDJ on the Samsung to use house rules."
                    reply.optBoolean("ok") -> reply.optString("message").ifBlank { null }
                    else -> reply.optString("error").ifBlank { COMMAND_FAILED }
                },
            )
        }
    }

    /** First-wins: a tap while a save or fetch runs is ignored. */
    private fun run(block: suspend (HomeDjMode?) -> Unit) {
        if (job?.isCompleted == false) return
        job = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, saved = false) }
            val mode = modeStore.mode.first()
            try {
                block(mode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SocketTimeoutException) {
                // Unknown outcome: Samsung may have saved; show what it has now.
                Lights.logFailure("House rules request", failure)
                mutableState.update { it.copy(message = "The Samsung didn't confirm in time. Tap refresh to see its saved rules.") }
            } catch (failure: Exception) {
                Lights.logFailure("House rules request", failure)
                mutableState.update { it.copy(message = SERVER_UNAVAILABLE) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private companion object {
        const val REPLY_MILLIS = 10_000
        const val COMMAND_FAILED = "Command failed. Check Wi-Fi connection."
        const val SERVER_UNAVAILABLE = "Server unavailable or pairing code incorrect."
    }
}
