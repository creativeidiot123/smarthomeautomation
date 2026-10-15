package dev.homedj.app

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test fun manualSaveIsNotSwallowedByPendingAutomaticJob() {
        assertNotEquals(OfflineAudioLibrary.workName("dQw4w9WgXcQ", automatic = true),
            OfflineAudioLibrary.workName("dQw4w9WgXcQ", automatic = false))
    }
}
