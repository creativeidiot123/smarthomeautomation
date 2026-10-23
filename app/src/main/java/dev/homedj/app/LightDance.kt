package dev.homedj.app

import kotlin.math.roundToInt
import org.json.JSONObject

/** Party: punchy hits on the beat that chase across the lights. Chill: colours that breathe and flow with it. Stored by name. */
internal enum class LightDanceStyle { PARTY, CHILL }

/**
 * Samsung-owned intent: while the Samsung plays music, its lit lights dance to it
 * ([LightDanceRunner]). [offsetMillis] moves the lights later (+) or earlier (-) than the
 * speakers, e.g. for a Bluetooth speaker's delay. The dance itself is never recorded.
 */
internal data class LightDance(
    val enabled: Boolean = false,
    val style: LightDanceStyle = LightDanceStyle.PARTY,
    val offsetMillis: Int = 0,
) {
    val valid: Boolean get() = offsetMillis in -LightDances.MAX_OFFSET..LightDances.MAX_OFFSET
}

/** One send to one light, due at [at] (elapsed-realtime ms). */
internal data class DanceCue(val at: Long, val bulb: Long, val light: VariedLight)

/** What the music is doing at one beat, for [LightDances.beat]. */
internal data class DanceBeat(val index: Long, val at: Long, val periodMillis: Int, val energy: Float, val surge: Boolean)

/**
 * The choreography, pure so it can be tested: each beat becomes a few cues, at most about two
 * sends per beat plus one colour per light per bar, which Matter-over-Wi-Fi bulbs keep up with.
 * Light changes use the bulb's own transitions and never turn a bulb on (no OnOff, ExecuteIfOff clear).
 */
internal object LightDances {
    const val MAX_OFFSET = 500
    /** Without a steady beat (intro, ambient) the lights breathe at this pace in either style. */
    const val FREE_PERIOD_MILLIS = 1_500
    /** Vivid defaults for songs without usable artwork. */
    val DEFAULT_HUES = listOf(285, 195, 330, 45)

    /** `lightsDance`: the whole setting in `dance`; anything malformed is rejected. */
    fun edit(snapshot: LightsSnapshot, request: JSONObject): LightsSnapshot? =
        request.optJSONObject("dance")?.let { LightDanceCodec.read(it, strict = true) }?.let { snapshot.copy(dance = it) }

    /** Lights that dance: lit, and able to change brightness or colour. Sorted so the chase order is stable. */
    fun dancers(bulbs: List<Bulb>): List<Bulb> = bulbs.filter { it.on == true && fit(it, VariedLight(50, LightColor.Hue(0, 100), 0)) != null }
        .sortedBy(Bulb::id)

    fun beat(style: LightDanceStyle, beat: DanceBeat, bulbs: List<Bulb>, hues: List<Int>): List<DanceCue> {
        if (bulbs.isEmpty()) return emptyList()
        val palette = hues.ifEmpty { DEFAULT_HUES }
        val cues = if (style == LightDanceStyle.PARTY) party(beat, bulbs, palette) else chill(beat, bulbs, palette)
        return cues.mapNotNull { cue -> bulbs.firstOrNull { it.id == cue.bulb }?.let { fit(it, cue.light) }?.let { cue.copy(light = it) } }
    }

    /**
     * One light hits full on the beat while the one before it fades out over the beat, so the hit
     * chases round the room; a single light hits and falls within each beat. Each light takes the
     * next palette colour on its own beat of the bar (every two beats on a surge), instantly on a surge.
     */
    private fun party(beat: DanceBeat, bulbs: List<Bulb>, palette: List<Int>): List<DanceCue> {
        val period = beat.periodMillis
        val peak = (45 + 55 * beat.energy + if (beat.surge) 10 else 0).roundToInt().coerceIn(1, 100)
        val trough = (peak * if (beat.surge) 0.1 else 0.25).roundToInt().coerceAtLeast(2)
        val every = if (beat.surge) 2 else 4
        val n = bulbs.size
        val hit = Math.floorMod(beat.index, n)
        val previous = Math.floorMod(beat.index - 1, n)
        fun color(i: Int) = colorAt(beat.index, i, every, palette, 100)
        val colorMillis = if (beat.surge) 0 else minOf(period / 2, 400)
        val cues = mutableListOf(DanceCue(beat.at, bulbs[hit].id, VariedLight(peak, color(hit), 0)))
        if (n == 1) {
            cues += DanceCue(beat.at + period * 2 / 5, bulbs[0].id, VariedLight(trough, null, period / 2))
        } else {
            cues += DanceCue(beat.at, bulbs[previous].id, VariedLight(trough, color(previous), period * 9 / 10))
        }
        bulbs.indices.filter { it != hit && (n == 1 || it != previous) }.forEach { i ->
            color(i)?.let { cues += DanceCue(beat.at, bulbs[i].id, VariedLight(null, it, colorMillis)) }
        }
        return cues
    }

    /**
     * Each light breathes up and down over two beats, neighbours out of step, and drifts to the
     * next palette colour over a whole bar every two bars; brightness follows how loud it is.
     */
    private fun chill(beat: DanceBeat, bulbs: List<Bulb>, palette: List<Int>): List<DanceCue> {
        val period = beat.periodMillis
        val high = (30 + 55 * beat.energy + if (beat.surge) 10 else 0).roundToInt().coerceIn(1, 100)
        val low = (high * 0.4).roundToInt().coerceAtLeast(3)
        return bulbs.indices.mapNotNull { i ->
            val step = beat.index + i
            val level = if (Math.floorMod(step, 2L) == 0L) (if (Math.floorMod(step / 2, 2L) == 0L) high else low) else null
            val color = colorAt(beat.index, 2 * i, 8, palette, 85)
            if (level == null && color == null) null
            else DanceCue(beat.at, bulbs[i].id, VariedLight(level, color, if (color != null) 4 * period else 2 * period))
        }
    }

    /** The colour light [offset] takes on this beat, or null when it keeps its colour. Neighbours stay apart in the palette. */
    private fun colorAt(index: Long, offset: Int, every: Int, palette: List<Int>, saturation: Int): LightColor.Hue? {
        val since = index - offset
        if (Math.floorMod(since, every.toLong()) != 0L) return null
        val step = Math.floorDiv(since, every.toLong()) + offset
        return LightColor.Hue(palette[Math.floorMod(step, palette.size.toLong()).toInt()], saturation)
    }

    /** Drops what [bulb] cannot show; null when it is off or nothing is left. */
    fun fit(bulb: Bulb, light: VariedLight): VariedLight? {
        if (bulb.on != true) return null
        val level = light.level?.takeIf { bulb.reading?.dimmable != false }
        val color = light.color?.takeIf { bulb.reading?.color != false }
        return if (level == null && color == null) null else light.copy(level = level, color = color)
    }
}

/** Disk and LAN form inside the lights `experience` object; additive, so older builds ignore it. */
internal object LightDanceCodec {
    fun json(dance: LightDance): JSONObject = JSONObject().put("on", dance.enabled).put("style", dance.style.name)
        .put("offset", dance.offsetMillis)

    /** Strict (LAN edits): every field present and valid, else null. Lenient (disk, snapshots): defaults. */
    fun read(json: JSONObject?, strict: Boolean = false): LightDance? {
        if (json == null) return if (strict) null else LightDance()
        val style = LightDanceStyle.entries.firstOrNull { it.name == json.optString("style") }
        val offset = json.opt("offset")
        if (strict && (json.opt("on") !is Boolean || style == null || offset !is Int)) return null
        val dance = LightDance(json.optBoolean("on"), style ?: LightDanceStyle.PARTY, json.optInt("offset"))
        return if (dance.valid) dance else if (strict) null else dance.copy(offsetMillis = 0)
    }
}
