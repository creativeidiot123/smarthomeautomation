package dev.homedj.app

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamResolutionTest {
    @Test fun invalidOrUntrustedStreamCannotPlay() {
        assertNull(StreamResolution.playableUrl(null))
        assertNull(StreamResolution.playableUrl(Bundle().apply {
            putInt("err", -1); putInt("exitCode", 0); putString("stdout", "https://evilgooglevideo.com/audio")
        }))
        assertNull(StreamResolution.playableUrl(Bundle().apply {
            putInt("err", -1); putInt("exitCode", 1); putString("stdout", "https://r1.googlevideo.com/audio")
        }))
    }

    @Test fun acceptsOneValidHttpsGooglevideoUrl() {
        val url = "https://r1.googlevideo.com/videoplayback?id=123"
        assertEquals(url, StreamResolution.playableUrl(Bundle().apply {
            putInt("err", -1); putInt("exitCode", 0); putString("stdout", url)
        }))
    }
}
