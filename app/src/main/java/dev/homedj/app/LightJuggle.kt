package dev.homedj.app

import kotlin.random.Random

/**
 * Juggle: every [seconds] the lit, varying [bulbs] swap their recorded colours at random while
 * variation keeps drifting around whichever colour each one shows. Samsung-owned intent, set by
 * applying a scene with a juggle interval; the swaps themselves live only in [LightVariationRunner].
 * Its lights always have their own variation entry, which marks them on disk and the LAN (`j`).
 */
internal data class LightJuggle(val seconds: Int, val bulbs: Set<Long>)

internal object LightJuggles {
    val INTERVALS = listOf(15, 30, 60, 300)

    /** A scene's interval: null (older build, keep what was saved), 0 off, or 10 s..1 h. */
    fun validSeconds(seconds: Int?): Boolean = seconds == null || seconds == 0 || seconds in 10..3600

    /** Applying a scene: one juggle at a time; its lights leave any other juggle (latest change wins). */
    fun applyScene(current: LightJuggle?, scene: LightScene, bulbs: Collection<Long>): LightJuggle? {
        val seconds = scene.juggle ?: 0
        if (scene.variation?.enabled == true && seconds > 0 && bulbs.size >= 2) return LightJuggle(seconds, bulbs.toSet())
        return without(current, bulbs)
    }

    fun without(current: LightJuggle?, bulbs: Collection<Long>): LightJuggle? =
        current?.copy(bulbs = current.bulbs - bulbs.toSet())?.takeIf { it.bulbs.size >= 2 }

    /** Lights that juggle right now: on, with a known colour and variation on. Sorted for stable shuffles. */
    fun participants(snapshot: LightsSnapshot): List<Long> {
        val juggle = snapshot.variation.juggle ?: return emptyList()
        return snapshot.bulbs.filter { it.id in juggle.bulbs && it.on == true && it.color != null &&
            snapshot.variationOf(it.id).enabled }.map(Bulb::id).sorted().takeIf { it.size >= 2 }.orEmpty()
    }

    /**
     * A new random assignment (light -> light whose colour it shows) where, when possible, every
     * light shows a different source than [current] (missing entries show their own colour).
     */
    fun shuffle(current: Map<Long, Long>, ids: List<Long>, random: Random): Map<Long, Long> {
        if (ids.size < 2) return emptyMap()
        repeat(20) {
            val sources = ids.shuffled(random)
            val next = ids.zip(sources).toMap()
            if (ids.all { next[it] != (current[it] ?: it) }) return next
        }
        // Rare fallback: shift every light to the next source in order, which always moves each one.
        val sources = ids.map { current[it]?.takeIf(ids::contains) ?: it }
        return ids.mapIndexed { index, id -> id to sources[(index + 1) % ids.size] }.toMap()
    }

    /** The colour [id] shows under [assignment], or null for its own (or one it cannot show). */
    fun color(snapshot: LightsSnapshot, assignment: Map<Long, Long>, id: Long): LightColor? {
        val source = assignment[id]?.takeIf { it != id } ?: return null
        val bulb = snapshot.bulbs.firstOrNull { it.id == id } ?: return null
        val color = snapshot.bulbs.firstOrNull { it.id == source }?.color ?: return null
        val supported = if (color is LightColor.Hue) bulb.reading?.color != false else bulb.reading?.white != false
        return color.takeIf { supported && it != bulb.color }
    }

    /** Null when fewer than two lights remain or the interval is invalid. */
    fun read(seconds: Int, bulbs: Set<Long>): LightJuggle? =
        LightJuggle(seconds, bulbs).takeIf { seconds in 10..3600 && bulbs.size >= 2 }
}
