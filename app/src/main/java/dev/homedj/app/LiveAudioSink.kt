package dev.homedj.app

import android.content.Context
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One temporary PCM sink on Samsung; not a second Media3 queue or library owner. */
internal object LiveAudioSink {
    suspend fun play(context: Context, receive: () -> ByteArray) = withContext(Dispatchers.IO) {
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
                .setBufferSizeInBytes(maxOf(minimum, LiveAudioFormat.CHUNK_BYTES * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = output
            if (output.state != AudioTrack.STATE_INITIALIZED) throw IOException("Audio output unavailable")
            var started = false
            var primed = 0
            while (true) {
                val pcm = receive()
                if (!LiveAudioFormat.validPacket(pcm.size)) throw IOException("Invalid audio packet")
                var position = 0
                while (position < pcm.size) {
                    val written = output.write(pcm, position, pcm.size - position, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) throw IOException("Samsung audio output stopped")
                    position += written
                }
                primed += pcm.size
                if (!started && primed >= LiveAudioFormat.CHUNK_BYTES * 5) {
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
