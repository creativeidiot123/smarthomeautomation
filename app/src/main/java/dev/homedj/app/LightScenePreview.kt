package dev.homedj.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Scene editor live preview: the lights show the draft at once (no fade) and go back to the
 * states recorded when the preview began once the editor closes or a light is unticked.
 * Main-confined in [LightsViewModel]; [take] runs at send time, so a newer draft that replaced a
 * queued send still sends every bulb that differs from what the lights last showed.
 */
internal class LightScenePreview {
    /** Recorded bulb states when the preview began; kept until every restore was sent. */
    private var start: Map<Long, Bulb>? = null
    /** What each touched bulb should show now. */
    private val target = LinkedHashMap<Long, LightChange>()
    /** Last change sent per bulb; absent means it still shows its starting state. */
    private val shown = HashMap<Long, LightChange>()

    var open = false
        private set

    /** The starting states, for "Capture HomeDJ settings" while the lights show the draft. */
    val base: List<Bulb>? get() = start?.values?.toList()

    fun show(draft: Map<Long, LightChange>, bulbs: List<Bulb>) {
        val base = start ?: bulbs.associateBy(Bulb::id).also { start = it }
        open = true
        target.keys.filter { draft[it]?.isEmpty != false }.forEach { id -> back(base, id) }
        draft.forEach { (id, change) -> if (id in base && !change.isEmpty) target[id] = change.copy(fadeSeconds = 0) }
    }

    fun end() {
        if (!open) return
        open = false
        start?.let { base -> target.keys.toList().forEach { id -> back(base, id) } }
    }

    /** Bulbs whose target differs from what they last showed; marked shown until [failed]. */
    fun take(): Map<Long, LightChange> = pending().also(shown::putAll)

    /** The owner did not confirm every bulb, so what they show is unknown; the next [take] sends them again. */
    fun failed(changes: Map<Long, LightChange>) = changes.keys.forEach { shown[it] = LightChange() }

    /** After the sends drain with the editor closed, the next preview records fresh states. */
    fun settled() {
        if (open || pending().isNotEmpty()) return
        start = null
        target.clear()
        shown.clear()
    }

    private fun pending(): Map<Long, LightChange> {
        val base = start ?: return emptyMap()
        return target.filter { (id, change) -> change != (shown[id] ?: restore(base[id])) }
    }

    private fun back(base: Map<Long, Bulb>, id: Long) {
        val change = restore(base[id])
        if (change == null) target.remove(id) else target[id] = change
    }

    companion object {
        /** A recorded state as one change; off only switches off ([LightsPolicy.commands]). Null: unknown. */
        fun restore(bulb: Bulb?): LightChange? =
            bulb?.let { LightChange(it.on, it.level, it.color) }?.takeUnless { it.isEmpty }

        /** LAN `lightsPreview` request: one entry per bulb, the scene change format. */
        fun request(changes: Map<Long, LightChange>): JSONObject = JSONObject().put("command", "lightsPreview")
            .put("changes", JSONArray().also { array ->
                changes.forEach { (id, change) -> array.put(LightsCodec.putChange(JSONObject().put("id", id), change)) }
            })

        /** Samsung-side validation: bounded, distinct paired bulbs, valid instant changes. Null: reject. */
        fun changes(array: JSONArray?, bulbs: List<Bulb>): Map<Long, LightChange>? {
            if (array == null || array.length() !in 1..LightsPolicy.MAX_BULBS) return null
            val entries = (0 until array.length()).map { index ->
                val entry = array.optJSONObject(index) ?: return null
                val id = entry.optLong("id")
                val change = LightsCodec.change(entry)
                if (bulbs.none { it.id == id } || !LightsPolicy.valid(change) || change.fadeSeconds != 0) return null
                id to change
            }
            return entries.toMap().takeIf { it.size == entries.size }
        }
    }
}
