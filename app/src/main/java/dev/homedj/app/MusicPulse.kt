package dev.homedj.app

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToLong

/** Where a deck's audible media time was at [at] (`SystemClock.elapsedRealtime`), and how fast it runs. */
internal class DeckClock(val positionUs: Long, val at: Long, val speed: Float)

/** The audible deck's music now; its beat grid mapped to elapsed-realtime milliseconds. */
internal class MusicNow(val reading: PulseReading, private val clock: DeckClock) {
    fun wallMillis(mediaUs: Long): Long = clock.at + ((mediaUs - clock.positionUs) / clock.speed / 1000.0).roundToLong()

    /** Grid beats heard from [from] (elapsed ms) on, as media µs; empty while no beat is locked. */
    fun beatsFrom(from: Long, count: Int): List<Long> {
        if (!reading.locked) return emptyList()
        val fromUs = clock.positionUs + ((from - clock.at) * 1000.0 * clock.speed).roundToLong()
        val first = Math.floorDiv(fromUs - reading.beatUs + reading.periodUs - 1, reading.periodUs)
        return (0 until count).map { reading.beatUs + (first + it) * reading.periodUs }
    }

    /** Beat length as heard, in milliseconds (playback speed applied). */
    val periodMillis: Long get() = (reading.periodUs / clock.speed / 1000.0).roundToLong()
}

/**
 * What the Samsung's music is doing, for the light show. Each player deck's audio sink feeds its
 * own [Deck] on that deck's playback thread; readers on other threads see immutable snapshots.
 * Nothing here is persisted, logged or sent; no PCM is kept beyond the tracker's 6 s envelope.
 */
internal object MusicPulse {
    private val decks = CopyOnWriteArrayList<Deck>()

    class Deck internal constructor() {
        private var tracker: BeatTracker? = null
        private var channels = 0
        private var lastBuffer: ByteBuffer? = null
        private var lastUs = Long.MIN_VALUE
        @Volatile var clock: DeckClock? = null
            private set
        @Volatile var reading = PulseReading()
            private set
        @Volatile var volume = 1f
        @Volatile var playing = false

        /** 16-bit PCM only (Media3's default decoder output); anything else is not analysed. */
        fun configure(encoding: Int, sampleRate: Int, channelCount: Int) {
            val usable = encoding == C.ENCODING_PCM_16BIT && sampleRate > 0 && channelCount > 0
            tracker = if (usable) BeatTracker(sampleRate) else null
            channels = channelCount
            flush()
        }

        /** Each decoded buffer once: a sink that could not take all of it is handed the same buffer again. */
        fun analyze(buffer: ByteBuffer, presentationUs: Long) {
            val tracker = tracker ?: return
            if (buffer === lastBuffer && presentationUs == lastUs) return
            lastBuffer = buffer
            lastUs = presentationUs
            val pcm = buffer.duplicate().order(buffer.order())
            tracker.begin(presentationUs)
            val frameBytes = 2 * channels
            while (pcm.remaining() >= frameBytes) {
                var sum = 0
                repeat(channels) { sum += pcm.getShort().toInt() }
                tracker.add(sum / (channels * 32_768f))
            }
            reading = tracker.reading
        }

        /** The sink's audible position, sampled at most every 50 ms. */
        fun anchor(positionUs: Long, speed: Float) {
            if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET || speed <= 0f) return
            val now = SystemClock.elapsedRealtime()
            if (now - (clock?.at ?: 0L) >= 50L) clock = DeckClock(positionUs, now, speed)
        }

        fun flush() {
            tracker?.reset()
            lastBuffer = null
            lastUs = Long.MIN_VALUE
            reading = PulseReading()
            clock = null
        }
    }

    fun deck(): Deck = Deck().also(decks::add)

    fun release(deck: Deck) {
        decks.remove(deck)
    }

    /** The loudest playing deck with a fresh clock (the incoming one wins a crossfade's second half). */
    fun now(at: Long = SystemClock.elapsedRealtime()): MusicNow? = decks.mapNotNull { deck ->
        val clock = deck.clock ?: return@mapNotNull null
        if (!deck.playing || deck.volume < 0.05f || at - clock.at > STALE_MILLIS) null else Triple(deck.volume, deck.reading, clock)
    }.maxByOrNull { it.first }?.let { (_, reading, clock) -> MusicNow(reading, clock) }

    private const val STALE_MILLIS = 1_000L
}

/** Media3's sink with the deck's music analysis on the side; audio passes through unchanged. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class PulseAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private val deck = MusicPulse.deck()

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        deck.configure(inputFormat.pcmEncoding, inputFormat.sampleRate, inputFormat.channelCount)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        deck.analyze(buffer, presentationTimeUs)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = super.getCurrentPositionUs(sourceEnded)
        .also { deck.anchor(it, playbackParameters.speed) }

    override fun play() {
        super.play()
        deck.playing = true
    }

    override fun pause() {
        super.pause()
        deck.playing = false
    }

    override fun setVolume(volume: Float) {
        super.setVolume(volume)
        deck.volume = volume
    }

    override fun flush() {
        super.flush()
        deck.flush()
    }

    override fun reset() {
        super.reset()
        deck.playing = false
        deck.flush()
    }

    override fun release() {
        super.release()
        MusicPulse.release(deck)
    }
}
