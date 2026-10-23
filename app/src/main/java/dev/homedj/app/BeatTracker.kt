package dev.homedj.app

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What one deck's music is doing: a beat grid in that deck's media time and how loud it is.
 * [periodUs] is 0 while no steady beat is found (intros, ambient, speech).
 */
internal data class PulseReading(
    val periodUs: Long = 0,
    /** One beat on the grid (media µs); the others follow every [periodUs] either side. */
    val beatUs: Long = 0,
    /** 0..1: the music now against this song's own recent level, so quiet and loud songs both span it. */
    val energy: Float = 0f,
    /** Clearly louder than the last several seconds: a drop or a chorus. */
    val surge: Boolean = false,
    val silent: Boolean = true,
    /** 0..1: how clearly bass attacks (kick, bass notes) land on the beats; 0 without a grid. */
    val bass: Float = 0f,
    /** 0..1: how clearly bright attacks (snare, hats, claps) land on beats and half-beats; 0 without a grid. */
    val snap: Float = 0f,
    /**
     * 0..1: sustained sound in the voice range (~250 Hz-4 kHz) against the whole mix. A heuristic:
     * held guitars, keys and pads read as voice too; drum hits do not.
     */
    val voice: Float = 0f,
) {
    val locked: Boolean get() = periodUs > 0
}

/**
 * Real-time beat tracker for one deck, fed mono samples on that deck's playback thread; not
 * thread-safe. A 100 Hz onset envelope (bass and broadband energy rises) gives the tempo by
 * weighted autocorrelation over the last 6 s and the beat phase by a comb sum over the last 4 s;
 * a gentle phase-locked grid keeps the beat steady between estimates. Each band's attacks are then
 * checked against that grid ([PulseReading.bass], [PulseReading.snap]), and the floor of the voice
 * band against the mix ([PulseReading.voice]). No FFT, no allocation per sample.
 */
internal class BeatTracker(private val sampleRate: Int) {
    private val hop = maxOf(1, sampleRate / FRAME_RATE)
    private val hopUs = hop * 1_000_000.0 / sampleRate
    private val sampleUs = 1_000_000.0 / sampleRate
    private val lowAlpha = alpha(LOW_HZ)
    private val voiceBelowAlpha = alpha(VOICE_LOW_HZ)
    private val voiceUpToAlpha = alpha(VOICE_HIGH_HZ)
    private var low1 = 0f
    private var low2 = 0f
    private var previous = 0f
    private var voiceBelow = 0f
    private var voiceUpTo = 0f
    private var voiceSum = 0.0
    private var lowSum = 0.0
    private var diffSum = 0.0
    private var powerSum = 0.0
    private var count = 0
    /** Media time just after the last sample added. */
    private var nextUs = Double.NaN
    private var frameEndUs = 0.0

    private val onset = FloatArray(HISTORY)
    private val lowRises = FloatArray(HISTORY)
    private val diffRises = FloatArray(HISTORY)
    private var frames = 0
    private val lowDb = FloatArray(REFERENCE)
    private val diffDb = FloatArray(REFERENCE)
    private var shortPower = 0.0
    private var midPower = 0.0
    private var longPower = 0.0
    /** Quietest voice-band frame since the last publish, and its smoothed level (dB). */
    private var voiceMin = Float.POSITIVE_INFINITY
    private var voiceFloor = Float.NaN

    private var gridPeriodUs = 0.0
    private var gridBeatUs = 0.0
    private var disagree = 0
    private var candidateUs = 0.0
    private var agree = 0
    private var weak = 0

    /** Latest reading; replaced (never mutated) every [PUBLISH_FRAMES]. */
    var reading = PulseReading()
        private set

    /** The next sample is at [presentationUs]; a jump (not a gapless continuation) starts over. */
    fun begin(presentationUs: Long) {
        if (!nextUs.isNaN() && kotlin.math.abs(presentationUs - nextUs) > JUMP_US) reset()
        nextUs = presentationUs.toDouble()
    }

    fun add(sample: Float) {
        low1 += lowAlpha * (sample - low1)
        low2 += lowAlpha * (low1 - low2)
        val diff = sample - previous
        previous = sample
        voiceBelow += voiceBelowAlpha * (sample - voiceBelow)
        voiceUpTo += voiceUpToAlpha * (sample - voiceUpTo)
        val voice = voiceUpTo - voiceBelow
        voiceSum += voice * voice
        lowSum += low2 * low2
        diffSum += diff * diff
        powerSum += sample * sample
        nextUs += sampleUs
        if (++count == hop) frame()
    }

    fun reset() {
        low1 = 0f; low2 = 0f; previous = 0f
        voiceBelow = 0f; voiceUpTo = 0f; voiceMin = Float.POSITIVE_INFINITY; voiceFloor = Float.NaN
        lowSum = 0.0; diffSum = 0.0; powerSum = 0.0; voiceSum = 0.0; count = 0
        nextUs = Double.NaN
        frames = 0
        gridPeriodUs = 0.0; gridBeatUs = 0.0; disagree = 0; weak = 0; candidateUs = 0.0; agree = 0
        reading = PulseReading()
    }

    private fun frame() {
        val low = decibels(lowSum / count)
        val diff = decibels(diffSum / count)
        val power = powerSum / count
        voiceMin = minOf(voiceMin, decibels(voiceSum / count))
        lowSum = 0.0; diffSum = 0.0; powerSum = 0.0; voiceSum = 0.0; count = 0
        frameEndUs = nextUs
        val slot = frames % REFERENCE
        // Rises only, against the last few frames: an attack, not a held note.
        val heard = frames >= REFERENCE && decibels(power) >= SILENT_DB
        val lowRise = if (heard) maxOf(0f, low - lowDb.average().toFloat()) else 0f
        val diffRise = if (heard) maxOf(0f, diff - diffDb.average().toFloat()) else 0f
        lowDb[slot] = low
        diffDb[slot] = diff
        lowRises[frames % HISTORY] = lowRise
        diffRises[frames % HISTORY] = diffRise
        onset[frames % HISTORY] = lowRise + DIFF_WEIGHT * diffRise
        if (frames == 0) { shortPower = power; midPower = power; longPower = power }
        shortPower += SHORT * (power - shortPower)
        midPower += MID * (power - midPower)
        longPower += LONG * (power - longPower)
        frames++
        if (frames >= MIN_FRAMES && frames % ESTIMATE_FRAMES == 0) estimate()
        if (frames % PUBLISH_FRAMES == 0) publish()
    }

    private fun publish() {
        val short = decibels(shortPower)
        val long = decibels(longPower)
        // The quietest frame of each 100 ms: held notes keep it up, drum hits between them do not.
        voiceFloor = if (voiceFloor.isNaN()) voiceMin else voiceFloor + VOICE_SMOOTHING * (voiceMin - voiceFloor)
        voiceMin = Float.POSITIVE_INFINITY
        val silent = short < SILENT_DB
        reading = PulseReading(
            periodUs = gridPeriodUs.roundToLong(),
            beatUs = gridBeatUs.roundToLong(),
            energy = ((short - long + 10f) / 16f).coerceIn(0f, 1f),
            surge = frames >= MIN_FRAMES && decibels(midPower) - long > SURGE_DB,
            silent = silent,
            bass = onGrid(lowRises, 1),
            snap = onGrid(diffRises, 2),
            voice = if (silent) 0f else ((voiceFloor - short - VOICE_FLOOR_DB) / VOICE_SPAN_DB).coerceIn(0f, 1f),
        )
    }

    /**
     * How much more of [rises]' last 2 s of attacks sit on the grid ([perBeat] points per beat,
     * ±1 frame) than on average: 0 for scattered noise or without a grid, 1 for a clear pattern.
     */
    private fun onGrid(rises: FloatArray, perBeat: Int): Float {
        if (gridPeriodUs <= 0 || frames < GRID_FRAMES) return 0f
        fun back(b: Int) = rises[(frames - 1 - b) % HISTORY]
        val mean = (0 until GRID_FRAMES).sumOf { back(it).toDouble() } / GRID_FRAMES
        if (mean <= 1e-6) return 0f
        val step = gridPeriodUs / perBeat
        var pointUs = gridBeatUs + floor((frameEndUs - gridBeatUs) / step) * step
        var sum = 0.0
        var points = 0
        while (true) {
            val b = ((frameEndUs - pointUs) / hopUs - 0.5).roundToInt()
            if (b > GRID_FRAMES - 2) break
            if (b >= 1) {
                sum += maxOf(back(b - 1), back(b), back(b + 1))
                points++
            }
            pointUs -= step
        }
        if (points == 0) return 0f
        return ((sum / points / mean - GRID_FLOOR) / GRID_SPAN).toFloat().coerceIn(0f, 1f)
    }

    private fun alpha(hz: Double): Float = (1 - exp(-2 * PI * hz / sampleRate)).toFloat()

    /** Tempo by autocorrelation, phase by comb sum, then a small step of the grid towards them. */
    private fun estimate() {
        val n = minOf(frames, HISTORY)
        val raw = DoubleArray(n) { onset[(frames - n + it) % HISTORY].toDouble() }
        val mean = raw.average()
        val centred = DoubleArray(n) { raw[it] - mean }
        val r0 = centred.sumOf { it * it } / n
        if (r0 <= 1e-9) return weaken()
        val r = DoubleArray(2 * MAX_LAG + 3) { lag -> if (lag >= n) 0.0 else
            (lag until n).sumOf { centred[it] * centred[it - lag] } / (n - lag) }
        val currentLag = gridPeriodUs / hopUs
        val score = DoubleArray(MAX_LAG + 2) { lag -> if (lag < MIN_LAG - 1) Double.NEGATIVE_INFINITY else {
            val base = tempoWeight(lag) * (r[lag] + 0.5 * r[2 * lag])
            if (currentLag > 0) base * (1 + 0.6 * exp(-0.5 * square((lag - currentLag) / (0.04 * currentLag)))) else base
        } }
        val best = (MIN_LAG..MAX_LAG).maxByOrNull { score[it] } ?: return weaken()
        if (r[best] / r0 < MIN_CONFIDENCE) return weaken()
        weak = 0
        val period = best + parabola(score[best - 1], score[best], score[best + 1])
        val phase = phase(raw, period)
        val beatUs = frameEndUs - (phase + 0.5) * hopUs
        follow(period * hopUs, beatUs)
    }

    /** Frames back from the newest frame to the strongest beat position, comb-summed over ~4 s. */
    private fun phase(raw: DoubleArray, period: Double): Double {
        val beats = minOf(((raw.size - 2) / period).toInt(), (COMB_FRAMES / period).toInt()).coerceAtLeast(1)
        fun sum(back: Double) = (0 until beats).sumOf { k -> at(raw, back + k * period) * 0.85.pow(k) }
        val sums = DoubleArray(period.toInt() + 1) { sum(it.toDouble()) }
        val best = sums.indices.maxByOrNull { sums[it] } ?: 0
        val before = sums[if (best == 0) sums.size - 1 else best - 1]
        val after = sums[if (best == sums.size - 1) 0 else best + 1]
        return best + parabola(before, sums[best], after)
    }

    private fun at(raw: DoubleArray, back: Double): Double {
        val position = raw.size - 1 - back
        if (position < 0) return 0.0
        val index = floor(position).toInt()
        val fraction = position - index
        return raw[index] * (1 - fraction) + (if (index + 1 < raw.size) raw[index + 1] else raw[index]) * fraction
    }

    /**
     * A grid starts only once the same tempo is heard [LOCK_ESTIMATES] times in a row (a chance peak in
     * noise is not a beat); then small steps towards each estimate, and a different tempo heard for ~1 s
     * in a row replaces it.
     */
    private fun follow(periodUs: Double, beatUs: Double) {
        if (gridPeriodUs <= 0) {
            agree = if (candidateUs > 0 && kotlin.math.abs(periodUs - candidateUs) / candidateUs < SAME_TEMPO) agree + 1 else 1
            candidateUs = periodUs
            if (agree >= LOCK_ESTIMATES) { gridPeriodUs = periodUs; gridBeatUs = beatUs; disagree = 0; agree = 0 }
            return
        }
        val k = Math.round((beatUs - gridBeatUs) / gridPeriodUs)
        val predicted = gridBeatUs + k * gridPeriodUs
        if (kotlin.math.abs(periodUs - gridPeriodUs) / gridPeriodUs < SAME_TEMPO) {
            disagree = 0
            gridPeriodUs += 0.25 * (periodUs - gridPeriodUs)
            gridBeatUs = predicted + 0.3 * (beatUs - predicted)
        } else if (++disagree >= 4) {
            disagree = 0
            gridPeriodUs = periodUs
            gridBeatUs = beatUs
        }
    }

    private fun weaken() {
        agree = 0
        if (++weak >= 4) { gridPeriodUs = 0.0; gridBeatUs = 0.0; disagree = 0 }
    }

    private fun tempoWeight(lag: Int): Double = exp(-0.5 * square(ln(lag / CENTRE_LAG) / ln(2.0)))

    private fun square(value: Double) = value * value

    private fun parabola(before: Double, peak: Double, after: Double): Double {
        val curve = before - 2 * peak + after
        return if (curve >= 0 || !curve.isFinite()) 0.0 else (0.5 * (before - after) / curve).coerceIn(-0.5, 0.5)
    }

    private fun decibels(power: Double): Float = (10 * log10(power + 1e-10)).toFloat()

    private companion object {
        const val FRAME_RATE = 100
        const val LOW_HZ = 150.0
        const val HISTORY = 600 // 6 s
        const val MIN_FRAMES = 300 // 3 s before the first estimate
        const val ESTIMATE_FRAMES = 25
        const val PUBLISH_FRAMES = 10
        const val COMB_FRAMES = 400.0
        const val REFERENCE = 3
        const val DIFF_WEIGHT = 0.5f
        /** 181..70 BPM; 120 BPM is the most likely. */
        const val MIN_LAG = 33
        const val MAX_LAG = 86
        const val CENTRE_LAG = 50.0
        const val MIN_CONFIDENCE = 0.2 // noise peaks near 0.13; a steady beat is 0.8+
        const val SAME_TEMPO = 0.08
        const val LOCK_ESTIMATES = 3
        const val JUMP_US = 100_000.0
        const val SILENT_DB = -50f
        const val SURGE_DB = 3f
        const val SHORT = 0.05
        const val MID = 1.0 / 150
        const val LONG = 1.0 / 1000
        const val GRID_FRAMES = 200 // 2 s
        /** On-grid attacks this many times the average read 0; [GRID_SPAN] more read 1. */
        const val GRID_FLOOR = 2.5
        const val GRID_SPAN = 5.0
        const val VOICE_LOW_HZ = 250.0
        const val VOICE_HIGH_HZ = 4_000.0
        const val VOICE_SMOOTHING = 0.3f
        /** A voice-band floor this far below the mix reads 0; [VOICE_SPAN_DB] higher reads 1. */
        const val VOICE_FLOOR_DB = -24f
        const val VOICE_SPAN_DB = 16f
    }
}
