package dev.homedj.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAudioSourceTest {
    @Test fun strictVideoIds() {
        assertTrue(NativeAudioSource.validId("dQw4w9WgXcQ"))
        assertFalse(NativeAudioSource.validId("too-short"))
        assertFalse(NativeAudioSource.validId("../unsafe-ID"))
        assertFalse(NativeAudioSource.validId("dQw4w9WgXcQ?list=1"))
    }

    @Test fun onlyTrustedHttpsMediaHost() {
        assertTrue(NativeAudioSource.acceptedCdnUrl("https://r1---sn.googlevideo.com/videoplayback?id=42"))
        assertFalse(NativeAudioSource.acceptedCdnUrl("http://r1.googlevideo.com/videoplayback"))
        assertFalse(NativeAudioSource.acceptedCdnUrl("https://evilgooglevideo.com/videoplayback"))
        assertFalse(NativeAudioSource.acceptedCdnUrl("https://googlevideo.com.attacker.example/"))
        assertFalse(NativeAudioSource.acceptedCdnUrl("https://a@r1.googlevideo.com/"))
        assertFalse(NativeAudioSource.acceptedCdnUrl("file:///etc/passwd"))
    }
}
