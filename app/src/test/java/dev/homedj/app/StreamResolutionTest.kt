package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamResolutionTest {
    @Test fun invalidOrUntrustedStreamCannotPlay() {
        assertNull(StreamResolution.playableUrl(null, 0, -1))
        assertNull(StreamResolution.playableUrl("https://evilgooglevideo.com/audio", 0, -1))
        assertNull(StreamResolution.playableUrl("http://r1.googlevideo.com/audio", 0, -1))
        assertNull(StreamResolution.playableUrl("https://r1.googlevideo.com/audio", 1, -1))
        assertNull(StreamResolution.playableUrl("https://r1.googlevideo.com/audio", 0, 2))
        assertNull(StreamResolution.playableUrl("https://r1.googlevideo.com/audio\nhttps://r2.googlevideo.com/audio", 0, -1))
    }

    @Test fun acceptsOneHttpsGooglevideoAudioUrl() {
        val url = "https://r1.googlevideo.com/videoplayback?id=123"
        assertEquals(url, StreamResolution.playableUrl(url, 0, -1))
    }
}
