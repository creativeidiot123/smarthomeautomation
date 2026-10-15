package dev.homedj.app

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAudioPolicyTest {
    @Test fun backgroundPlaylistTransfersRequireUnmeteredConnection() {
        assertEquals(NetworkType.UNMETERED,
            OfflineAudioLibrary.networkForDownload("account", "playlist"))
        assertEquals(NetworkType.CONNECTED,
            OfflineAudioLibrary.networkForDownload(null, null))
        assertEquals(NetworkType.CONNECTED,
            OfflineAudioLibrary.networkForDownload("account", null))
    }

    @Test fun stoppedOrSwitchedServerMustNotContinueTransferring() {
        assertTrue(OfflineAudioLibrary.canTransfer(true, true))
        assertFalse(OfflineAudioLibrary.canTransfer(false, true))
        assertFalse(OfflineAudioLibrary.canTransfer(true, false))
    }
}
