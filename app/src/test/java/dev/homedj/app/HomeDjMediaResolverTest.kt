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
}
