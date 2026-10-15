package dev.homedj.app

import org.junit.Assert.assertEquals
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

    private val vision = "https://rr1---sn.googlevideo.com/videoplayback?id=1&c=VISIONOS&itag=140"
    private val android = "https://rr1---sn.googlevideo.com/videoplayback?id=1&c=ANDROID&itag=251"
    private val web = "https://rr1---sn.googlevideo.com/videoplayback?id=1&c=WEB&itag=251"

    @Test fun streamRequestsUseNewPipeHeadersPerClient() {
        val visionHeaders = NativeAudioSource.streamHeaders(vision)
        assertTrue(visionHeaders.getValue("User-Agent").contains("visionOS", ignoreCase = true))
        assertFalse(visionHeaders.containsKey("Origin"))
        assertEquals("identity", visionHeaders["Accept-Encoding"])
        assertTrue(NativeAudioSource.streamHeaders(android).getValue("User-Agent").contains("Firefox"))
        val webHeaders = NativeAudioSource.streamHeaders(web)
        assertEquals("https://www.youtube.com", webHeaders["Origin"])
        assertEquals("cross-site", webHeaders["Sec-Fetch-Site"])
    }

    @Test fun prefersVisionOsLinksThatNeedNoPoToken() {
        assertEquals(vision, NativeAudioSource.preferredStream(listOf(android to 160_000, vision to 128_000))?.first)
        assertEquals(android, NativeAudioSource.preferredStream(listOf(android to 160_000, web to 64_000))?.first)
    }

    @Test fun extractorRequestsGetBrowserAgentAndKeepConsentCookie() {
        val defaults = NativeAudioSource.extractorHeaders(mapOf(
            "Cookie" to listOf("SOCS=CAE="), "Authorization" to listOf("Bearer x"), "Host" to listOf("evil"),
        ))
        assertTrue(defaults.getValue("User-Agent").single().contains("Firefox"))
        assertEquals(listOf("SOCS=CAE="), defaults["Cookie"])
        assertFalse(defaults.containsKey("Authorization"))
        assertFalse(defaults.containsKey("Host"))
        val client = NativeAudioSource.extractorHeaders(mapOf("user-agent" to listOf("com.google.ios.youtube")))
        assertEquals(listOf("com.google.ios.youtube"), client["user-agent"])
        assertFalse(client.containsKey("User-Agent"))
    }

    @Test fun linkedPlaylistSongsTakeOnlyTheWatchParameter() {
        assertEquals("dQw4w9WgXcQ", NativeAudioSource.idFromWatchUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ",
            NativeAudioSource.idFromWatchUrl("https://www.youtube.com/watch?list=PL1&v=dQw4w9WgXcQ&index=2"))
        assertEquals(null, NativeAudioSource.idFromWatchUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQx"))
        assertEquals(null, NativeAudioSource.idFromWatchUrl("https://www.youtube.com/watch?vv=dQw4w9WgXcQ"))
        assertEquals(null, NativeAudioSource.idFromWatchUrl("https://www.youtube.com/channel/UC123"))
    }
}
