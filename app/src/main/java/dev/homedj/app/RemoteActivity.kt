package dev.homedj.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** One line of Samsung's change feed. [at] is this phone's wall-clock time, for display only. */
data class ActivityEntry(val id: Long, val at: Long, val source: String, val text: String)

/**
 * Samsung's in-memory feed of recent changes made from either phone, so both screens show who
 * changed what. Display only: never a command queue or playback truth. Lost with the process.
 * Entries hold titles only, never links, addresses or tokens; they are not written to Logcat.
 */
internal object RemoteActivity {
    const val SAMSUNG = "Samsung"
    const val ONEPLUS = "OnePlus"
    private const val LIMIT = 30
    private const val WIRE_LIMIT = 8
    private const val TEXT_LIMIT = 120

    private val mutableEntries = MutableStateFlow<List<ActivityEntry>>(emptyList())
    /** Newest first. */
    val entries: StateFlow<List<ActivityEntry>> = mutableEntries.asStateFlow()

    fun record(source: String, text: String) {
        mutableEntries.update { list ->
            val entry = ActivityEntry((list.firstOrNull()?.id ?: 0) + 1, System.currentTimeMillis(),
                source, text.take(TEXT_LIMIT))
            (listOf(entry) + list).take(LIMIT)
        }
    }

    /** Wire form for the status reply: newest first, age in seconds so clock skew does not matter. */
    fun toJson(entries: List<ActivityEntry>, now: Long): JSONArray = JSONArray().also { array ->
        entries.take(WIRE_LIMIT).forEach {
            array.put(JSONObject().put("id", it.id).put("ago", ((now - it.at) / 1_000).coerceAtLeast(0))
                .put("source", it.source).put("text", it.text))
        }
    }

    /** Null when Samsung's build has no feed. Bounded: the peer is paired but still validated. */
    fun fromJson(array: JSONArray?, now: Long): List<ActivityEntry>? {
        if (array == null) return null
        return buildList {
            for (index in 0 until minOf(array.length(), WIRE_LIMIT)) {
                val value = array.optJSONObject(index) ?: continue
                val text = value.optString("text").take(TEXT_LIMIT)
                if (text.isBlank()) continue
                val ago = value.optLong("ago", 0).coerceIn(0, 7 * 24 * 3_600)
                add(ActivityEntry(value.optLong("id", index.toLong()), now - ago * 1_000,
                    value.optString("source").take(20), text))
            }
        }
    }
}

internal fun breaksActivityText(settings: MusicBreakSettings): String =
    "Changed music breaks (" + (if (settings.enabled) "on" else "off") + ")"
