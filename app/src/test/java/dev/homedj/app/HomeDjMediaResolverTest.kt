package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeDjMediaResolverTest {
    @Test fun acceptsOnlyStrictInternalVideoIds() {
        val id = "dQw4w9WgXcQ"
        assertEquals(id, HomeDjMediaResolver.videoIdFromUri("homedj://youtube/$id"))
        assertNull(HomeDjMediaResolver.videoIdFromUri("homedj://youtube/$id?x=1"))
        assertNull(HomeDjMediaResolver.videoIdFromUri("homedj://youtube/../../etc"))
        assertNull(HomeDjMediaResolver.videoIdFromUri("homedj://youtube/short"))
        assertNull(HomeDjMediaResolver.videoIdFromUri("https://youtube.com/watch?v=$id"))
        assertNull(HomeDjMediaResolver.videoIdFromUri("homedj://attacker/$id"))
    }

    @Test fun numbersPlaybackRequestsLikeNewPipe() {
        assertEquals("https://h.googlevideo.com/videoplayback?id=1&rn=7",
            HomeDjMediaResolver.withRequestNumber("https://h.googlevideo.com/videoplayback?id=1", 7))
        val numbered = "https://h.googlevideo.com/videoplayback?id=1&rn=2"
        assertEquals(numbered, HomeDjMediaResolver.withRequestNumber(numbered, 9))
        assertEquals("https://h.googlevideo.com/other?id=1",
            HomeDjMediaResolver.withRequestNumber("https://h.googlevideo.com/other?id=1", 3))
    }
}
