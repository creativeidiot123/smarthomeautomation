package dev.homedj.app

/** Fixed little-endian stereo PCM format on the encrypted LAN connection. */
internal object LiveAudioFormat {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val CHUNK_BYTES = 3_840 // 20ms * 48kHz * 2 channels * 16 bits
    fun validPacket(bytes: Int): Boolean = bytes in 4..CHUNK_BYTES && bytes % 4 == 0
}
