package dev.homedj.app

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Each deck gets its own normalizer, ahead of Media3's speed and silence processors, and its own
 * [PulseAudioSink] so the light show hears the music it plays.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun normalizedRenderers(context: Context): RenderersFactory = object : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = PulseAudioSink(DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf(LoudnessNormalizer()))
        .build())
}

/**
 * Brings streamed and downloaded songs to one loudness. YouTube's own loudness value is not
 * available through NewPipe or in downloaded files, so each deck measures its audio as it plays.
 * Runs on that deck's playback thread; 16-bit PCM only (Media3's default output), else inactive.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class LoudnessNormalizer : BaseAudioProcessor() {
    private var leveler: LoudnessLeveler? = null
    private var frame = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount > 0) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val leveler = leveler ?: LoudnessLeveler(inputAudioFormat.sampleRate, channels).also { leveler = it }
        if (frame.size != channels) frame = ShortArray(channels)
        val output = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.remaining() >= channels * 2) {
            for (channel in 0 until channels) frame[channel] = inputBuffer.getShort()
            leveler.process(frame)
            for (channel in 0 until channels) output.putShort(frame[channel])
        }
        output.flip()
    }

    /** A seek or format change re-measures from the present gain, so it does not jump. */
    override fun onFlush() {
        if (!isActive) return
        leveler = LoudnessLeveler(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, leveler?.gainDb ?: 0.0)
    }

    /** A stopped deck starts its next song from unity gain. */
    override fun onReset() {
        leveler = null
    }
}

/**
 * ITU-R BS.1770 K-weighted, gated loudness over a sliding ~30 s window (a running average from
 * the song's start until then) steers a slowly moving gain toward [TARGET_LUFS]. A peak guard
 * keeps boosted audio from clipping. No look-ahead, so the crossfade timing is unchanged.
 */
internal class LoudnessLeveler(sampleRate: Int, private val channels: Int, startGainDb: Double = 0.0) {
    private val shelf = shelfFilter(sampleRate.toDouble())
    private val highPass = highPassFilter(sampleRate.toDouble())
    private val history = Array(channels) { DoubleArray(4) }
    private val blockFrames = (sampleRate / 10).coerceAtLeast(1)
    private val peakRelease = exp(-1.0 / (PEAK_RELEASE_SECONDS * sampleRate))
    private var blockEnergy = 0.0
    private var blockFrame = 0
    private var ungatedEnergy = 0.0
    private var ungatedBlocks = 0
    private var gatedEnergy = 0.0
    private var gatedBlocks = 0
    private var gain = dbToLinear(startGainDb)
    private var gainStep = 0.0
    private var peak = 0.0
    var gainDb = startGainDb
        private set

    /** Levels one interleaved 16-bit frame in place. */
    fun process(frame: ShortArray) {
        var loudest = 0.0
        for (channel in 0 until channels) {
            val sample = frame[channel] / 32_768.0
            val weighted = kWeight(sample, history[channel])
            blockEnergy += weighted * weighted
            loudest = maxOf(loudest, abs(sample * gain))
        }
        peak = if (loudest > peak) loudest else peak * peakRelease
        val scale = gain * if (peak > CEILING) CEILING / peak else 1.0
        for (channel in 0 until channels) {
            frame[channel] = (frame[channel] * scale).roundToInt().coerceIn(-32_768, 32_767).toShort()
        }
        gain += gainStep
        if (++blockFrame == blockFrames) endBlock()
    }

    private fun endBlock() {
        val energy = blockEnergy / blockFrames
        blockEnergy = 0.0
        blockFrame = 0
        if (energy > ABSOLUTE_GATE_ENERGY) {
            ungatedBlocks = min(ungatedBlocks + 1, WINDOW_BLOCKS)
            ungatedEnergy += (energy - ungatedEnergy) / ungatedBlocks
            // Quiet passages and fade-outs do not count toward the song's loudness.
            if (energy >= ungatedEnergy * RELATIVE_GATE) {
                gatedBlocks = min(gatedBlocks + 1, WINDOW_BLOCKS)
                gatedEnergy += (energy - gatedEnergy) / gatedBlocks
            }
        }
        val target = if (gatedBlocks == 0) gainDb
        else (TARGET_LUFS - loudness(gatedEnergy)).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
        val start = dbToLinear(gainDb)
        gainDb += (target - gainDb).coerceIn(-CUT_DB_PER_BLOCK, BOOST_DB_PER_BLOCK)
        gain = start
        gainStep = (dbToLinear(gainDb) - start) / blockFrames
    }

    private fun kWeight(input: Double, state: DoubleArray): Double {
        val shelved = shelf[0] * input + state[0]
        state[0] = shelf[1] * input - shelf[3] * shelved + state[1]
        state[1] = shelf[2] * input - shelf[4] * shelved
        val output = highPass[0] * shelved + state[2]
        state[2] = highPass[1] * shelved - highPass[3] * output + state[3]
        state[3] = highPass[2] * shelved - highPass[4] * output
        return output
    }

    companion object {
        /** The reference YouTube and most streaming apps normalize to. */
        const val TARGET_LUFS = -14.0
        const val MAX_BOOST_DB = 10.0
        const val MAX_CUT_DB = -12.0
        private const val CEILING = 0.98
        private const val WINDOW_BLOCKS = 300
        private const val CUT_DB_PER_BLOCK = 1.0
        private const val BOOST_DB_PER_BLOCK = 0.3
        private const val PEAK_RELEASE_SECONDS = 0.2
        private val ABSOLUTE_GATE_ENERGY = 10.0.pow((-70.0 + 0.691) / 10.0)
        private const val RELATIVE_GATE = 0.1

        fun loudness(energy: Double): Double = -0.691 + 10.0 * log10(energy)
        private fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

        /** BS.1770 stage 1 (head high-shelf) for any sample rate: b0, b1, b2, a1, a2. */
        private fun shelfFilter(rate: Double): DoubleArray {
            val k = tan(PI * 1_681.974450955533 / rate)
            val q = 0.7071752369554196
            val vh = 10.0.pow(3.999843853973347 / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            return doubleArrayOf((vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0,
                (vh - vb * k / q + k * k) / a0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
        }

        /** BS.1770 stage 2 (RLB high-pass) for any sample rate: b0, b1, b2, a1, a2. */
        private fun highPassFilter(rate: Double): DoubleArray {
            val k = tan(PI * 38.13547087602444 / rate)
            val q = 0.5003270373238773
            val a0 = 1.0 + k / q + k * k
            return doubleArrayOf(1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
        }
    }
}
