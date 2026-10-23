package dev.homedj.app

import android.content.Context
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One temporary PCM sink on Samsung; not a second Media3 queue or library owner. */
internal object LiveAudioSink {
    private val sinks = AtomicInteger()
    private const val MS = LiveAudioFormat.SAMPLE_RATE / 1000
    internal const val MIN_TARGET_FRAMES = 50 * MS // Queue on good Wi-Fi; also the start threshold.
    internal const val MAX_TARGET_FRAMES = 250 * MS // Queue on bad Wi-Fi: smooth beats instant there.
    private const val SLACK_FRAMES = 40 * MS // Jitter kept above the target before trimming.
    private const val GROW_FRAMES = 20 * MS
    private const val SHRINK_FRAMES = 10 * MS
    private const val CALM_MILLIS = 20_000L
    private const val BUFFER_FRAMES = LiveAudioFormat.SAMPLE_RATE / 2

    /**
     * Drops a packet once more than target + slack is queued and keeps dropping until the queue is
     * back at the target, so a burst after a Wi-Fi stall cannot leave the speakers behind.
     */
    internal fun trimsPacket(queuedFrames: Int, targetFrames: Int, trimming: Boolean): Boolean =
        queuedFrames > targetFrames + SLACK_FRAMES || (trimming && queuedFrames > targetFrames)

    /** Raises the queue target after each dropout; lowers it a step after a calm stretch. */
    internal fun adaptedTarget(targetFrames: Int, underran: Boolean, calmMillis: Long): Int = when {
        underran -> minOf(targetFrames + GROW_FRAMES, MAX_TARGET_FRAMES)
        calmMillis >= CALM_MILLIS -> maxOf(targetFrames - SHRINK_FRAMES, MIN_TARGET_FRAMES)
        else -> targetFrames
    }

    /** True while a cast owns the speakers, so the music schedule does not play over it. */
    val active: Boolean get() = sinks.get() > 0

    suspend fun play(context: Context, receive: () -> ByteArray) = withContext(Dispatchers.IO) {
        sinks.incrementAndGet()
        try {
            output(context, receive)
        } finally {
            sinks.decrementAndGet()
        }
    }

    private fun output(context: Context, receive: () -> ByteArray) {
        val format = AudioFormat.Builder()
            .setSampleRate(LiveAudioFormat.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val minimum = AudioTrack.getMinBufferSize(
            LiveAudioFormat.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) throw IOException("Unsupported Samsung audio output")
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw IOException("Samsung audio focus unavailable")
        }
        var track: AudioTrack? = null
        try {
            val output = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum, BUFFER_FRAMES * LiveAudioFormat.FRAME_BYTES))
                .setTransferMode(AudioTrack.MODE_STREAM)
                // Skips the deep-buffer mixer when Samsung offers a fast path; otherwise ignored.
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track = output
            if (output.state != AudioTrack.STATE_INITIALIZED) throw IOException("Audio output unavailable")
            // Android defaults to the full 500 ms capacity, which the trimmed queue never fills.
            val threshold = if (Build.VERSION.SDK_INT >= 31) {
                output.setStartThresholdInFrames(MIN_TARGET_FRAMES)
            } else {
                // Before API 31, reducing the effective buffer also lowers the start threshold
                // (and keeps the queue at the minimum target).
                output.setBufferSizeInFrames(MIN_TARGET_FRAMES)
            }
            if (threshold !in 1..MIN_TARGET_FRAMES + SLACK_FRAMES) {
                throw IOException("Audio start threshold unavailable")
            }
            var started = false
            var trimming = false
            var target = MIN_TARGET_FRAMES
            var underruns = 0
            var calmSince = 0L
            var framesWritten = 0 // Wraps like playbackHeadPosition, so their difference stays exact.
            while (true) {
                val pcm = receive()
                if (!LiveAudioFormat.validPacket(pcm.size)) throw IOException("Invalid audio packet")
                if (started) {
                    val now = SystemClock.elapsedRealtime()
                    val count = output.underrunCount
                    val next = adaptedTarget(target, count != underruns, now - calmSince)
                    if (next != target || count != underruns) calmSince = now
                    if (next < target) trimming = true // Latency only falls by dropping a packet.
                    underruns = count
                    target = next
                }
                // Live audio prefers a short gap over delay: the stale part of a burst is dropped.
                trimming = trimsPacket(framesWritten - output.playbackHeadPosition, target, trimming)
                if (trimming) continue
                val written = output.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
                if (written < 0) throw IOException("Samsung audio output stopped")
                framesWritten += written / LiveAudioFormat.FRAME_BYTES
                if (!started && framesWritten >= MIN_TARGET_FRAMES) {
                    output.play()
                    started = true
                    calmSince = SystemClock.elapsedRealtime()
                }
            }
        } finally {
            track?.let {
                runCatching { it.pause() }
                runCatching { it.flush() }
                it.release()
            }
            audio.abandonAudioFocusRequest(focus)
        }
    }
}
