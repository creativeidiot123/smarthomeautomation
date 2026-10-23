package dev.homedj.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Samsung's owner of the house-rule text (SharedPreferences `homedj_house_rules`, key `script`).
 * A save is accepted only when it parses and every name exists among the paired lights, groups,
 * scenes and schedules right now; [HouseRulesRunner] re-resolves names when a rule runs. The
 * OnePlus reads and saves through the LAN `houseRules`/`houseRulesSave` commands.
 */
internal object HouseRules {
    private const val TAG = "HomeDJLights"
    private const val SCRIPT = "script"
    val REMOTE_COMMANDS = setOf("houseRules", "houseRulesSave")

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow("")
    @Volatile private var loaded = false

    fun state(context: Context): StateFlow<String> {
        load(context)
        return mutableState.asStateFlow()
    }

    fun names(snapshot: LightsSnapshot) = HouseNames(
        snapshot.bulbs.map(Bulb::name), snapshot.configuration.groups.map(LightsGroup::name),
        snapshot.scenes.map(LightScene::name), snapshot.automation.rules.map(LightsRule::name).filter(String::isNotBlank),
    )

    suspend fun save(context: Context, script: String): Lights.Outcome = mutex.withLock {
        load(context)
        if (ModeStore(context).mode.first() != HomeDjMode.SERVER) {
            return@withLock Lights.Outcome(false, "House rules run on the Samsung in Server mode.")
        }
        val text = script.replace("\r\n", "\n").trimEnd()
        val parse = HouseScript.parse(text)
        val error = parse.error ?: HouseScript.unknownName(parse, names(Lights.state(context).value))
        if (error != null) return@withLock Lights.Outcome(false, error.message)
        val saved = withContext(Dispatchers.IO) { prefs(context).edit().putString(SCRIPT, text).commit() }
        if (!saved) {
            Log.w(TAG, "House rules save failed")
            return@withLock Lights.Outcome(false, "Couldn't save house rules. Try again.")
        }
        mutableState.value = text
        Lights.Outcome(true, when (parse.rules.size) {
            0 -> "House rules cleared"
            1 -> "1 house rule saved"
            else -> "${parse.rules.size} house rules saved"
        })
    }

    /** One LAN request; the reply always carries the saved text. */
    suspend fun handleRemote(context: Context, request: JSONObject): JSONObject {
        val outcome = when (request.optString("command")) {
            "houseRulesSave" -> (request.opt("script") as? String)?.takeIf { it.length <= HouseScript.MAX_CHARS }
                ?.let { save(context, it) } ?: Lights.Outcome(false, "Keep house rules under ${HouseScript.MAX_CHARS} characters.")
            else -> null
        }
        return JSONObject().put("ok", outcome?.ok ?: true)
            .also { reply -> outcome?.let { reply.put(if (it.ok) "message" else "error", it.message) } }
            .put("script", state(context).value)
    }

    private fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            mutableState.value = prefs(context).getString(SCRIPT, null).orEmpty()
            loaded = true
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("homedj_house_rules", Context.MODE_PRIVATE)
}
