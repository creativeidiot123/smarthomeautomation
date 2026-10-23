package dev.homedj.app

import coil3.disk.DiskCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicImageLoaderTest {
    @get:Rule val temporary = TemporaryFolder()

    private val small = "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"
    private val large = "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"

    @Test fun savedImagesSurviveReopeningAndSystemCacheCleanup() {
        val files = temporary.newFolder("files")
        val systemCache = temporary.newFolder("cache")
        val image = byteArrayOf(1, 2, 3, 4)
        val cache = musicArtworkDiskCache(files)
        try {
            assertEquals(128L * 1024 * 1024, cache.maxSize)
            write(cache, small, image)
        } finally {
            cache.shutdown()
        }
        systemCache.deleteRecursively()

        val reopened = musicArtworkDiskCache(files)
        try {
            val snapshot = checkNotNull(reopened.openSnapshot(small))
            snapshot.use {
                assertArrayEquals(image, reopened.fileSystem.read(it.data) { readByteArray() })
            }
            assertNull(reopened.openSnapshot(large))
        } finally {
            reopened.shutdown()
        }
    }

    @Test fun interruptedImageIsNotPublishedAndCanBeRetried() {
        val files = temporary.newFolder("files")
        val cache = musicArtworkDiskCache(files)
        try {
            val editor = checkNotNull(cache.openEditor(small))
            cache.fileSystem.write(editor.metadata) { writeUtf8("image/jpeg") }
            cache.fileSystem.write(editor.data) { write(byteArrayOf(1)) }
            assertNull(cache.openEditor(small))
            assertNull(cache.openSnapshot(small))
            editor.abort()
            assertNull(cache.openSnapshot(small))
            write(cache, small, byteArrayOf(2, 3))
            write(cache, large, byteArrayOf(4, 5))
        } finally {
            cache.shutdown()
        }

        val reopened = musicArtworkDiskCache(files)
        try {
            checkNotNull(reopened.openSnapshot(small)).use {
                assertArrayEquals(byteArrayOf(2, 3), reopened.fileSystem.read(it.data) { readByteArray() })
            }
            checkNotNull(reopened.openSnapshot(large)).use {
                assertArrayEquals(byteArrayOf(4, 5), reopened.fileSystem.read(it.data) { readByteArray() })
            }
        } finally {
            reopened.shutdown()
        }
    }

    private fun write(cache: DiskCache, url: String, bytes: ByteArray) {
        val editor = checkNotNull(cache.openEditor(url))
        cache.fileSystem.write(editor.metadata) { writeUtf8("image/jpeg") }
        cache.fileSystem.write(editor.data) { write(bytes) }
        editor.commit()
    }
}
