package dev.homedj.app

/** Fixed little-endian stereo PCM format on the encrypted LAN connection. */
internal object LiveAudioFormat {
    const val SAMPLE_RATE = 48_000
    const val CHUNK_BYTES = 3_840 // 20ms * 48kHz * 2 channels * 16 bits
    const val FRAME_BYTES = 4 // One stereo 16-bit sample.
    fun validPacket(bytes: Int): Boolean = bytes in 4..CHUNK_BYTES && bytes % 4 == 0
}
