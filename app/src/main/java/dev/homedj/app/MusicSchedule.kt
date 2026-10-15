package dev.homedj.app

import java.time.ZonedDateTime
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * One daily slot: from [startHour] (inclusive) to [endHour] (exclusive, 24 = midnight) play the
 * cached songs of [playlistIds] at [volume] percent. Slots never wrap past midnight or overlap.
 */
internal data class ScheduleSlot(
    val id: String,
    val startHour: Int,
    val endHour: Int,
    val volume: Int,
    val playlistIds: List<String>,
) {
    val label: String get() = String.format(Locale.ROOT, "%02d:00–%02d:00", startHour, endHour)
}

internal object ScheduleCodec {
    const val MAX_SLOTS = 24
    const val MAX_PLAYLISTS = 10

    fun encode(slots: List<ScheduleSlot>): String = JSONObject()
        .put("version", 1)
        .put("slots", JSONArray().also { array ->
            slots.forEach { slot ->
                array.put(JSONObject().put("id", slot.id).put("start", slot.startHour)
                    .put("end", slot.endHour).put("volume", slot.volume)
                    .put("playlists", JSONArray(slot.playlistIds)))
            }
        }).toString()

    /** Invalid slots are dropped one by one so a single bad entry never erases the schedule. */
    fun decode(json: String): List<ScheduleSlot> {
        val root = JSONObject(json)
        require(root.optInt("version") == 1) { "Unsupported schedule" }
        val array = root.optJSONArray("slots") ?: return emptyList()
        val slots = mutableListOf<ScheduleSlot>()
        for (index in 0 until minOf(array.length(), MAX_SLOTS)) {
            val value = array.optJSONObject(index) ?: continue
            val ids = value.optJSONArray("playlists") ?: continue
            val slot = ScheduleSlot(
                id = value.optString("id"),
                startHour = value.optInt("start", -1),
                endHour = value.optInt("end", -1),
                volume = value.optInt("volume", -1),
                playlistIds = (0 until minOf(ids.length(), MAX_PLAYLISTS)).map { ids.optString(it) },
            )
            if (SchedulePolicy.validate(slots + slot) == null) slots += slot
        }
        return slots
    }
}

/** What the sole player should do when the schedule is reconciled. */
internal sealed interface ScheduleAction {
    data class Start(val slot: ScheduleSlot, val key: String) : ScheduleAction
    data object Pause : ScheduleAction
    data object None : ScheduleAction
    data object NoSlot : ScheduleAction
    data object Busy : ScheduleAction
}

internal enum class ScheduleTrigger { BOUNDARY, QUEUE_ENDED, RESUME }

/**
 * The queue's origin is item 0: scheduled queues tag it with the slot instance key, anything a
 * person or the OnePlus plays has none. [playing] means the player intends to and can make sound;
 * [ended] is re-read at decision time so a queue started meanwhile is never replaced.
 */
internal data class QueueState(
    val slotKey: String?,
    val hasItems: Boolean,
    val playing: Boolean,
    val ended: Boolean = false,
)

internal object SchedulePolicy {
    fun validate(slots: List<ScheduleSlot>): String? {
        if (slots.size > ScheduleCodec.MAX_SLOTS) return "At most ${ScheduleCodec.MAX_SLOTS} slots"
        slots.forEach { slot ->
            if (slot.id.length !in 1..64 || slot.id.any { !it.isLetterOrDigit() && it != '-' }) {
                return "Invalid slot"
            }
            if (slot.startHour !in 0..23 || slot.endHour !in slot.startHour + 1..24) {
                return "End time must be after start time"
            }
            if (slot.volume !in 0..100) return "Invalid volume"
            if (slot.playlistIds.isEmpty()) return "Choose at least one playlist"
            if (slot.playlistIds.size > ScheduleCodec.MAX_PLAYLISTS ||
                slot.playlistIds.any { !validPlaylistId(it) }) return "Invalid playlist"
        }
        if (slots.map(ScheduleSlot::id).toSet().size != slots.size) return "Invalid slot"
        val sorted = slots.sortedBy(ScheduleSlot::startHour)
        if (sorted.zipWithNext().any { (first, second) -> second.startHour < first.endHour }) {
            return "Slots overlap"
        }
        return null
    }

    fun validPlaylistId(id: String): Boolean =
        id.length in 2..150 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }

    /** The slot covering [now] and its per-day instance key, so yesterday's queue is never "current". */
    fun active(slots: List<ScheduleSlot>, now: ZonedDateTime): Pair<ScheduleSlot, String>? =
        slots.firstOrNull { now.hour >= it.startHour && now.hour < it.endHour }
            ?.let { it to it.id + "@" + now.toLocalDate().toEpochDay() }

    /** The next wall-clock hour after [now] at which any slot starts or ends. */
    fun nextBoundary(slots: List<ScheduleSlot>, now: ZonedDateTime): ZonedDateTime? {
        val hours = slots.flatMap { listOf(it.startHour, it.endHour % 24) }.toSortedSet()
        return (0L..1L).asSequence().flatMap { day ->
            val date = now.toLocalDate().plusDays(day)
            hours.asSequence().map { date.atTime(it, 0).atZone(now.zone) }
        }.firstOrNull { it.isAfter(now) }
    }

    fun decide(
        trigger: ScheduleTrigger,
        active: Pair<ScheduleSlot, String>?,
        queue: QueueState,
        castActive: Boolean,
    ): ScheduleAction {
        if (castActive) return if (trigger == ScheduleTrigger.RESUME) ScheduleAction.Busy else ScheduleAction.None
        if (active == null) {
            return when {
                trigger == ScheduleTrigger.RESUME -> ScheduleAction.NoSlot
                trigger == ScheduleTrigger.BOUNDARY && queue.slotKey != null -> ScheduleAction.Pause
                else -> ScheduleAction.None
            }
        }
        val start = ScheduleAction.Start(active.first, active.second)
        return when (trigger) {
            ScheduleTrigger.RESUME -> start
            ScheduleTrigger.QUEUE_ENDED -> if (queue.ended) start else ScheduleAction.None
            ScheduleTrigger.BOUNDARY -> when {
                // Already this slot today (a re-armed or repeated boundary): keep its queue, even paused.
                queue.slotKey == active.second && queue.hasItems -> ScheduleAction.None
                // A person's queue that is still playing finishes first; its end starts the slot.
                queue.slotKey == null && queue.hasItems && queue.playing -> ScheduleAction.None
                else -> start
            }
        }
    }

    /** Puts [inserted] (the index right after [current]) next in a shuffled play [order]. */
    fun playNextOrder(order: IntArray, current: Int, inserted: Int): IntArray {
        val shifted = order.map { if (it >= inserted) it + 1 else it }.toMutableList()
        shifted.add(shifted.indexOf(current) + 1, inserted)
        return shifted.toIntArray()
    }
}
