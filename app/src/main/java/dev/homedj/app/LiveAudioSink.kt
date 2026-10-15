package dev.homedj.app

import android.content.Context
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One temporary PCM sink on Samsung; not a second Media3 queue or library owner. */
internal object LiveAudioSink {
    private val sinks = AtomicInteger()
    private const val START_FRAMES = LiveAudioFormat.SAMPLE_RATE / 5 // 200 ms primed before playing.
    private const val MAX_QUEUED_FRAMES = LiveAudioFormat.SAMPLE_RATE * 2 / 5 // 400 ms latency cap.
    private const val BUFFER_FRAMES = LiveAudioFormat.SAMPLE_RATE / 2

    /** Keeps a packet while less than the latency cap is queued for the speakers. */
    internal fun keepsPacket(queuedFrames: Int): Boolean = queuedFrames < MAX_QUEUED_FRAMES

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
                .build()
            track = output
            if (output.state != AudioTrack.STATE_INITIALIZED) throw IOException("Audio output unavailable")
            var started = false
            var framesWritten = 0 // Wraps like playbackHeadPosition, so their difference stays exact.
            while (true) {
                val pcm = receive()
                if (!LiveAudioFormat.validPacket(pcm.size)) throw IOException("Invalid audio packet")
                // The queue absorbs Wi-Fi jitter; a burst after a stall beyond the cap is dropped so the
                // speakers never fall permanently behind the OnePlus.
                if (!keepsPacket(framesWritten - output.playbackHeadPosition)) continue
                val written = output.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
                if (written < 0) throw IOException("Samsung audio output stopped")
                framesWritten += written / LiveAudioFormat.FRAME_BYTES
                if (!started && framesWritten >= START_FRAMES) {
                    output.play()
                    started = true
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
