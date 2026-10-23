package dev.homedj.app

import android.content.Context
import android.os.SystemClock
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live "alive" variation: a smooth random drift around a bulb's saved settings. [hue] (warmth for
 * white light), [saturation] and [brightness] are each 0 (still) to 100 % of the widest drift;
 * [speed] runs from 0 (slowest) to 100 (fastest).
 */
internal data class LightVariation(
    val enabled: Boolean = false,
    val hue: Int = LightVariationCodec.SUBTLE,
    val saturation: Int = LightVariationCodec.SUBTLE,
    val brightness: Int = LightVariationCodec.SUBTLE,
    val speed: Int = LightVariationCodec.SLOW,
) {
    val valid: Boolean get() = listOf(hue, saturation, brightness, speed).all { it in 0..100 } &&
        (!enabled || hue + saturation + brightness > 0)
}

/**
 * Samsung-owned intent: [all] unless a bulb's group ([LightsGroup.variation]) or the bulb itself
 * ([individual]) has its own; [juggle] swaps colours. Resolve with [variationOf].
 */
internal data class LightsVariation(
    val all: LightVariation = LightVariation(),
    val individual: Map<Long, LightVariation> = emptyMap(),
    val juggle: LightJuggle? = null,
)

/** Latest change wins down the chain: the light's own value, else its group's, else all lights'. */
internal fun LightsSnapshot.variationOf(bulb: Long): LightVariation =
    variation.individual[bulb] ?: LightsGroups.group(configuration, bulb)?.variation ?: variation.all

/** Where a varying bulb's last step left it, relative to its record; the next step walks on from here. */
internal data class VariationDrift(val hue: Int = 0, val saturation: Int = 0, val level: Int = 0, val kelvin: Int = 0)

/** One step for one bulb: targets reached by the bulb's own transition over [millis]. */
internal data class VariedLight(val level: Int?, val color: LightColor?, val millis: Int, val restore: Boolean = false,
    val drift: VariationDrift = VariationDrift()) {
    /** Never turns a bulb on: MoveToLevel without OnOff, and steps skip a bulb that is off. */
    fun commands(): List<MatterCommand> = listOfNotNull(
        color?.let { colorCommand(it, millis / 100, executeIfOff = restore) },
        level?.let { levelCommand(it, millis / 100, withOnOff = false, executeIfOff = restore) },
    )
}

internal object LightVariations {
    // The widest drift (100 %) either side of the record; level is a share of it, at least MIN_LEVEL.
    private const val HUE = 60
    private const val SATURATION = 40
    private const val LEVEL_PERCENT = 50
    private const val MIN_LEVEL = 15
    private const val WARMTH = 1_600
    private const val RESTORE_MILLIS = 2_000

    /** About 20 s per step at speed 0 down to 2 s at 100, even steps on a log scale. */
    fun stepMillis(speed: Int): Int = (20_000 * 0.1.pow(speed.coerceIn(0, 100) / 100.0)).roundToInt()

    /**
     * The next step of [bulb]'s drift around its recorded (base) settings, continuing from [drift],
     * or null when it is off, nothing it supports is known, or [variation] is off. Unknown values
     * are never invented.
     */
    fun step(bulb: Bulb, variation: LightVariation, random: Random, drift: VariationDrift = VariationDrift()): VariedLight? {
        if (!variation.enabled || bulb.on != true) return null
        var next = VariationDrift()
        val level = bulb.level?.takeIf { variation.brightness > 0 && bulb.reading?.dimmable != false }?.let { base ->
            val range = maxOf(spread(MIN_LEVEL, variation.brightness), base * LEVEL_PERCENT * variation.brightness / 10_000)
            next = next.copy(level = walk(drift.level, range, random))
            reflect(base + next.level, 1, 100)
        }
        val color = when (val base = bulb.color) {
            is LightColor.Hue -> if (bulb.reading?.color == false || variation.hue + variation.saturation == 0) null else {
                if (variation.hue > 0) next = next.copy(hue = walk(drift.hue, spread(HUE, variation.hue), random))
                if (variation.saturation > 0) next = next.copy(saturation =
                    walk(drift.saturation, spread(SATURATION, variation.saturation), random))
                LightColor.Hue(Math.floorMod(base.degrees + next.hue, 360), reflect(base.saturationPercent + next.saturation, 0, 100))
            }
            is LightColor.White -> if (bulb.reading?.white == false || variation.hue == 0) null else {
                next = next.copy(kelvin = walk(drift.kelvin, spread(WARMTH, variation.hue), random))
                LightColor.White(reflect(base.kelvin + next.kelvin, LightsPolicy.KELVIN.first, LightsPolicy.KELVIN.last))
            }
            null -> null
        }
        if (level == null && color == null) return null
        // Per-bulb jitter keeps several lights from moving in lockstep.
        val millis = (stepMillis(variation.speed) * random.nextDouble(0.85, 1.15)).roundToInt()
        return VariedLight(level, color, millis, drift = next)
    }

    private fun spread(widest: Int, amount: Int) = maxOf(1, widest * amount / 100)

    /**
     * Random walk within +-[range]: each step moves at most half the range on from the last, so the
     * bulb's chained transitions read as one smooth drift instead of jumps across the whole range.
     */
    private fun walk(from: Int, range: Int, random: Random): Int {
        val stride = maxOf(1, range / 2)
        return reflect(from.coerceIn(-range, range) + random.nextInt(-stride, stride + 1), -range, range)
    }

    /**
     * A varied bulb that stops varying (setting off, or the bulb turned off) returns to its record once.
     * [juggled] varies around another light's colour; [sendColor] (juggling now, or just stopped)
     * sends the colour even when hue and saturation do not vary, so swaps show and swap back.
     */
    fun plan(bulb: Bulb, variation: LightVariation, varied: Boolean, random: Random,
        juggled: LightColor? = null, sendColor: Boolean = false, drift: VariationDrift = VariationDrift()): VariedLight? {
        val base = juggled?.let { bulb.copy(color = it) } ?: bulb
        return step(base, variation, random, drift)?.let { if (sendColor && it.color == null) it.copy(color = base.color) else it }
            ?: if (varied) restore(bulb) else null
    }

    /**
     * [id]'s step planned from [snapshot], the owner's record at send time: [source]'s colour too,
     * so a juggle never sends a colour a newer scene replaced. [swapped]: it last showed another's colour.
     */
    fun next(snapshot: LightsSnapshot, id: Long, varied: Boolean, random: Random, source: Long? = null,
        swapped: Boolean = false, drift: VariationDrift = VariationDrift()): VariedLight? {
        val bulb = snapshot.bulbs.firstOrNull { it.id == id } ?: return null
        val juggled = source?.let { LightJuggles.color(snapshot, mapOf(id to it), id) }
        return plan(bulb, snapshot.variationOf(id), varied, random, juggled, sendColor = juggled != null || swapped, drift = drift)
    }

    /** Back to the recorded settings once variation ends; also while off, so it comes back on unvaried. */
    fun restore(bulb: Bulb): VariedLight? {
        val level = bulb.level?.takeIf { bulb.reading?.dimmable != false }
        val color = bulb.color?.takeIf { if (it is LightColor.Hue) bulb.reading?.color != false else bulb.reading?.white != false }
        return if (level == null && color == null) null else VariedLight(level, color, RESTORE_MILLIS, restore = true)
    }

    /**
     * `lightsVariation`: no `id` is the all-light setting and, latest change wins, clears each group's
     * and light's own. `lightsGroupVariation` (`groupId`) sets a group and clears its lights' own.
     */
    fun edit(snapshot: LightsSnapshot, request: JSONObject): LightsSnapshot? {
        if (request.optString("command") == "lightsGroupVariation") return editGroup(snapshot, request)
        val current = snapshot.variation
        val id = if (request.has("id")) request.optLong("id") else null
        if (id != null && snapshot.bulbs.none { it.id == id }) return null
        if (id != null && request.optBoolean("clear")) return snapshot.copy(variation = current.copy(
            individual = current.individual - id, juggle = LightJuggles.without(current.juggle, listOf(id))))
        val variation = LightVariationCodec.read(request, "variation")?.takeIf { it.valid } ?: return null
        // A light's own (or the all-light) setting is newer than the scene that started a juggle.
        return if (id == null) snapshot.copy(variation = LightsVariation(variation), configuration = snapshot.configuration
            .copy(groups = snapshot.configuration.groups.map { it.copy(variation = null) }))
            else snapshot.copy(variation = current.copy(individual = current.individual + (id to variation),
                juggle = LightJuggles.without(current.juggle, listOf(id))))
    }

    private fun editGroup(snapshot: LightsSnapshot, request: JSONObject): LightsSnapshot? {
        val group = snapshot.configuration.groups.firstOrNull { it.id == request.optString("groupId") } ?: return null
        val variation = if (request.optBoolean("clear")) null
            else LightVariationCodec.read(request, "variation")?.takeIf { it.valid } ?: return null
        val groups = snapshot.configuration.groups.map { if (it.id == group.id) it.copy(variation = variation) else it }
        return snapshot.copy(configuration = snapshot.configuration.copy(groups = groups),
            variation = if (variation == null) snapshot.variation else joined(snapshot.variation, group.bulbs))
    }

    /** Lights that join a group, or whose group's variation changed, follow the group again. */
    fun joined(current: LightsVariation, bulbs: Collection<Long>) = current.copy(
        individual = current.individual - bulbs.toSet(), juggle = LightJuggles.without(current.juggle, bulbs))

    /** Applying a scene is an individual change for its lights; a legacy scene (null) leaves variation alone. */
    fun applyScene(current: LightsVariation, scene: LightScene, bulbs: Collection<Long>): LightsVariation =
        current.copy(individual = current.individual + bulbs.mapNotNull { id ->
            (scene.variations[id] ?: scene.variation)?.let { id to it }
        }, juggle = if (scene.variation == null) current.juggle else LightJuggles.applyScene(current.juggle, scene, bulbs))

    fun withoutBulb(current: LightsVariation, id: Long) = current.copy(individual = current.individual - id,
        juggle = LightJuggles.without(current.juggle, listOf(id)))

    /** Unreachable bulbs back off instead of tying up the Matter controller every step. */
    fun backoffMillis(failures: Int): Long = (30_000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(480_000L)

    private fun reflect(value: Int, low: Int, high: Int): Int = when {
        value < low -> low + (low - value)
        value > high -> high - (value - high)
        else -> value
    }.coerceIn(low, high)
}

/**
 * Compact integer forms on disk and the LAN (the maximum snapshot must fit one encrypted frame).
 * Each value is written as [legacy] under its key (`v`, `variation`, `all`), which older builds
 * read, plus the full [code] under the key + `2` unless the legacy form already says the same.
 * Juggle adds per-light `j` 1, a top-level `juggle` interval and scene `j` seconds. Additive only:
 * older builds ignore these fields.
 */
internal object LightVariationCodec {
    /** Amounts and speeds of the older two-step choices (Subtle/High, Slow/Fast). */
    const val SUBTLE = 20
    const val HIGH = 75
    const val SLOW = 25
    const val FAST = 75

    /** Full form: bit 0 on, then 7 bits each for hue, saturation, brightness and speed. */
    fun code(variation: LightVariation): Int = (if (variation.enabled) 1 else 0) or (variation.hue shl 1) or
        (variation.saturation shl 8) or (variation.brightness shl 15) or (variation.speed shl 22)

    fun read(code: Int): LightVariation? = if (code !in 0 until (1 shl 29)) null else
        LightVariation(code and 1 != 0, code shr 1 and 127, code shr 8 and 127, code shr 15 and 127, code shr 22 and 127)
            .takeIf { listOf(it.hue, it.saturation, it.brightness, it.speed).all { value -> value <= 100 } }

    /** The 6-bit form older builds read: bit 1 on, 2 high, 4 fast, 8 hue, 16 saturation, 32 brightness. */
    fun legacy(variation: LightVariation): Int = listOf(variation.enabled,
        maxOf(variation.hue, variation.saturation, variation.brightness) >= 50, variation.speed >= 50,
        variation.hue > 0, variation.saturation > 0, variation.brightness > 0)
        .foldIndexed(0) { bit, code, on -> if (on) code or (1 shl bit) else code }

    fun fromLegacy(code: Int): LightVariation? {
        if (code !in 0..63) return null
        val amount = if (code and 2 != 0) HIGH else SUBTLE
        fun channel(bit: Int) = if (code and bit != 0) amount else 0
        return LightVariation(code and 1 != 0, channel(8), channel(16), channel(32), if (code and 4 != 0) FAST else SLOW)
    }

    fun put(json: JSONObject, key: String, variation: LightVariation): JSONObject = json.put(key, legacy(variation))
        .also { if (fromLegacy(legacy(variation)) != variation) it.put(key + "2", code(variation)) }

    /** Null when [key] is absent; an invalid value (rejected by callers) when malformed. */
    fun read(json: JSONObject, key: String): LightVariation? {
        json.opt(key + "2")?.let { return integer(it)?.let(::read) ?: INVALID }
        val value = json.opt(key) ?: return null
        return integer(value)?.let(::fromLegacy) ?: INVALID
    }

    private fun integer(value: Any): Int? = (value as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt()

    private val INVALID = LightVariation(enabled = true, hue = 0, saturation = 0, brightness = 0)

    fun json(variation: LightsVariation) = put(JSONObject(), "all", variation.all)
        .put("individual", JSONArray().also { array ->
            variation.individual.forEach { (id, value) -> array.put(put(JSONObject().put("id", id), "v", value)
                .also { entry -> if (variation.juggle?.bulbs?.contains(id) == true) entry.put("j", 1) }) }
        }).also { json -> variation.juggle?.let { json.put("juggle", it.seconds) } }

    /** Drops malformed entries and bulbs that are no longer paired. */
    fun read(json: JSONObject?, bulbs: List<Bulb>): LightsVariation {
        if (json == null) return LightsVariation()
        val entries = json.optJSONArray("individual") ?: JSONArray()
        val juggling = mutableSetOf<Long>()
        val individual = (0 until minOf(entries.length(), LightsPolicy.MAX_BULBS)).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optLong("id")
            read(entry, "v")?.takeIf { it.valid && bulbs.any { bulb -> bulb.id == id } }
                ?.also { if (entry.optInt("j") == 1) juggling += id }?.let { id to it }
        }.toMap()
        return LightsVariation(read(json, "all")?.takeIf { it.valid } ?: LightVariation(), individual,
            LightJuggles.read(json.optInt("juggle"), juggling))
    }
}

/**
 * Samsung-only loop in [RemoteServerService]'s scope (its wake/Wi-Fi locks keep it running). Each
 * bulb gets its own random step through [Lights.vary]; nothing is committed, so the record keeps
 * the base. Suspends without work while no lit light has variation on, and wakes on setting changes.
 * Juggle swaps live here too, in memory only: a restart starts from each light's own colour.
 */
internal class LightVariationRunner(private val context: Context, private val random: Random = Random.Default) {
    private val nextAt = HashMap<Long, Long>()
    /** Where each varying light's drift has got to; the next step walks on from it. */
    private val drift = HashMap<Long, VariationDrift>()
    private val varied = HashSet<Long>()
    private val failures = HashMap<Long, Int>()
    /** Light -> light whose recorded colour it shows; reshuffled every juggle interval. */
    private var assignment: Map<Long, Long> = emptyMap()
    /** Lights last sent another light's colour; their own colour is sent back explicitly. */
    private val swapped = HashSet<Long>()
    private var juggle: LightJuggle? = null
    private var juggleAt = 0L

    suspend fun run() {
        val state = Lights.state(context)
        while (true) {
            // Lights that are off wait here without polling; turning one on publishes a new state.
            val snapshot = state.first { s -> varied.isNotEmpty() || s.bulbs.any { it.on == true && s.variationOf(it.id).enabled } }
            val ids = snapshot.bulbs.map(Bulb::id).toSet()
            nextAt.keys.retainAll(ids); drift.keys.retainAll(ids); varied.retainAll(ids); failures.keys.retainAll(ids); swapped.retainAll(ids)
            val juggling = juggle(snapshot, SystemClock.elapsedRealtime())
            ids.forEach { id ->
                val now = SystemClock.elapsedRealtime()
                val source = if (id in juggling) assignment[id]?.takeIf { it != id } else null
                if ((nextAt[id] ?: 0L) <= now) nextAt[id] = now + step(id, now, source)
            }
            val wait = (listOfNotNull(nextAt.values.minOrNull(), juggleAt.takeIf { juggle != null }).minOrNull() ?: 0L) -
                SystemClock.elapsedRealtime()
            // A changed setting starts, retunes or restores every light right away.
            withTimeoutOrNull(wait.coerceIn(250L, IDLE_MILLIS)) { state.first { it.variation != snapshot.variation } }
                ?.let { nextAt.clear() }
        }
    }

    /** The lights juggling now; a due interval swaps their colours at random and steps them right away. */
    private fun juggle(snapshot: LightsSnapshot, now: Long): List<Long> {
        val current = snapshot.variation.juggle
        if (current != juggle) {
            juggle = current
            assignment = emptyMap()
            juggleAt = now + (current?.seconds ?: 0) * 1000L
        }
        val ids = LightJuggles.participants(snapshot)
        if (current != null && now >= juggleAt) {
            juggleAt = now + current.seconds * 1000L
            assignment = LightJuggles.shuffle(assignment, ids, random)
            ids.forEach(nextAt::remove)
        }
        return ids
    }

    private suspend fun step(id: Long, now: Long, source: Long?): Long = try {
        val result = Lights.vary(context, id, id in varied, now, random, source, swapped = id in swapped,
            drift = drift[id] ?: VariationDrift())
        failures -= id
        // A source showing this light's own colour marks it swapped too: one extra own-colour send, never a stale one.
        if (result != null) { if (source != null && !result.restore) swapped += id else swapped -= id }
        when {
            result == null || result.restore -> { varied -= id; drift -= id; IDLE_MILLIS }
            else -> { varied += id; drift[id] = result.drift; result.millis.toLong() }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Lights.logFailure("Light variation", failure)
        val count = (failures[id] ?: 0) + 1
        failures[id] = count
        LightVariations.backoffMillis(count)
    }

    private companion object {
        /** Re-check for a light that is off, fading, or has variation off. */
        const val IDLE_MILLIS = 5_000L
    }
}
