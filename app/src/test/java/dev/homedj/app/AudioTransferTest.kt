package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTransferTest {
    @Test fun parsesOnlyConsistentContentRanges() {
        assertEquals(AudioTransfer.ContentRange(524288, 1048575, 3912345),
            AudioTransfer.parseContentRange("bytes 524288-1048575/3912345"))
        listOf(null, "", "bytes */3912345", "bytes 10-5/100", "bytes 0-100/100", "bytes 0-1/x", "items 0-1/2")
            .forEach { assertNull(it, AudioTransfer.parseContentRange(it)) }
    }

    @Test fun partialFilesAreScopedToTrackAndJobKind() {
        val id = "dQw4w9WgXcQ"
        assertTrue(OfflineAudioLibrary.isPartialOf("$id.140.part", id, automatic = false))
        assertTrue(OfflineAudioLibrary.isPartialOf("$id.251.auto.part", id, automatic = true))
        assertFalse(OfflineAudioLibrary.isPartialOf("$id.251.auto.part", id, automatic = false))
        assertFalse(OfflineAudioLibrary.isPartialOf("$id.140.part", id, automatic = true))
        assertFalse(OfflineAudioLibrary.isPartialOf("$id.m4a", id, automatic = false))
        assertFalse(OfflineAudioLibrary.isPartialOf("aaaaaaaaaaa.140.part", id, automatic = false))
    }
}
