package dev.homedj.app

import java.time.ZonedDateTime
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * One weekly slot: from [startHour] (inclusive) to [endHour] (exclusive, 24 = midnight) play the
 * cached songs of [playlistIds] at [volume] percent. Slots never wrap past midnight or overlap
 * on a shared weekday. Days use ISO numbering (Monday = 1).
 */
internal data class ScheduleSlot(
    val id: String,
    val startHour: Int,
    val endHour: Int,
    val volume: Int,
    val playlistIds: List<String>,
    val days: Set<Int> = (1..7).toSet(),
    val startMinute: Int = 0,
    val endMinute: Int = 0,
) {
    val startMinutes: Int get() = startHour * 60 + startMinute
    val endMinutes: Int get() = endHour * 60 + endMinute
    val label: String get() = String.format(Locale.ROOT, "%02d:%02d–%02d:%02d", startHour, startMinute, endHour, endMinute)
}

/** Strict 24-hour input; only an end time may use 24:00. */
internal fun scheduleTimeMinutes(text: String, allowMidnight: Boolean = false): Int? {
    if (!text.matches(Regex("[0-9]{1,2}:[0-9]{2}"))) return null
    val (hour, minute) = text.split(':').map(String::toInt)
    if (allowMidnight && hour == 24 && minute == 0) return 1440
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}

internal fun scheduleTimeText(minutes: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)

internal object ScheduleCodec {
    const val MAX_SLOTS = 24
    const val MAX_PLAYLISTS = 10

    fun encode(slots: List<ScheduleSlot>): String = JSONObject()
        .put("version", if (slots.all { it.days == (1..7).toSet() &&
            it.startMinute == 0 && it.endMinute == 0 }) 1 else 2)
        .put("slots", JSONArray().also { array ->
            slots.forEach { slot ->
                array.put(JSONObject().put("id", slot.id).put("start", slot.startHour)
                    .put("end", slot.endHour).put("volume", slot.volume)
                    .put("playlists", JSONArray(slot.playlistIds))
                    .put("days", JSONArray(slot.days.sorted()))
                    .put("startMinute", slot.startMinute).put("endMinute", slot.endMinute))
            }
        }).toString()

    /** Invalid slots are dropped one by one so a single bad entry never erases the schedule. */
    fun decode(json: String): List<ScheduleSlot> {
        val root = JSONObject(json)
        val version = root.optInt("version")
        require(version in 1..2) { "Unsupported schedule" }
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
                days = if (version == 1) (1..7).toSet() else value.optJSONArray("days")?.let { days ->
                    (0 until minOf(days.length(), 8)).map { days.optInt(it, -1) }.toSet()
                }.orEmpty(),
                startMinute = if (version == 1) 0 else value.optInt("startMinute", -1),
                endMinute = if (version == 1) 0 else value.optInt("endMinute", -1),
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
            if (slot.startHour !in 0..23 || slot.endHour !in 0..24 ||
                slot.startMinute !in 0..59 || slot.endMinute !in 0..59 ||
                (slot.endHour == 24 && slot.endMinute != 0) || slot.endMinutes <= slot.startMinutes) {
                return "End time must be after start time"
            }
            if (slot.days.isEmpty() || slot.days.any { it !in 1..7 }) return "Choose valid days"
            if (slot.volume !in 0..100) return "Invalid volume"
            if (slot.playlistIds.isEmpty()) return "Choose at least one playlist"
            if (slot.playlistIds.size > ScheduleCodec.MAX_PLAYLISTS ||
                slot.playlistIds.any { !validPlaylistId(it) }) return "Invalid playlist"
        }
        if (slots.map(ScheduleSlot::id).toSet().size != slots.size) return "Invalid slot"
        if (slots.indices.any { index -> slots.drop(index + 1).any { other ->
                val slot = slots[index]
                slot.days.any { it in other.days } && slot.startMinutes < other.endMinutes &&
                    other.startMinutes < slot.endMinutes
            } }) {
            return "Slots overlap"
        }
        return null
    }

    fun validPlaylistId(id: String): Boolean =
        id.length in 2..150 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }

    /** The slot covering [now] and its per-day instance key, so yesterday's queue is never "current". */
    fun active(slots: List<ScheduleSlot>, now: ZonedDateTime): Pair<ScheduleSlot, String>? =
        slots.firstOrNull { now.dayOfWeek.value in it.days &&
            now.hour * 60 + now.minute >= it.startMinutes && now.hour * 60 + now.minute < it.endMinutes }
            ?.let { it to it.id + "@" + now.toLocalDate().toEpochDay() }

    /** Selected weekdays use the server's local wall clock; 24:00 ends on the following date. */
    fun nextBoundary(slots: List<ScheduleSlot>, now: ZonedDateTime): ZonedDateTime? {
        return (-1L..7L).asSequence().flatMap { day ->
            val date = now.toLocalDate().plusDays(day)
            slots.asSequence().filter { date.dayOfWeek.value in it.days }.flatMap { slot ->
                sequenceOf(date.atStartOfDay().plusMinutes(slot.startMinutes.toLong()).atZone(now.zone),
                    date.atStartOfDay().plusMinutes(slot.endMinutes.toLong()).atZone(now.zone))
            }
        }.filter { it.isAfter(now) }.minOrNull()
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
