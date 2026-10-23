package dev.homedj.app

/** Fixed little-endian stereo PCM format on the encrypted LAN connection. */
internal object LiveAudioFormat {
    const val SAMPLE_RATE = 48_000
    const val CHUNK_BYTES = 3_840 // 20ms * 48kHz * 2 channels * 16 bits
    // The OnePlus sends 10 ms packets for lower delay; any aligned size up to CHUNK_BYTES is valid,
    // so either phone may still run a build that sends or expects 20 ms packets.
    const val SEND_BYTES = CHUNK_BYTES / 2
    const val FRAME_BYTES = 4 // One stereo 16-bit sample.
    fun validPacket(bytes: Int): Boolean = bytes in 4..CHUNK_BYTES && bytes % 4 == 0
}
