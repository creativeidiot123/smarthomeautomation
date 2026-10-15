package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDjPageTest {
    @Test
    fun playerLibrarySetupAreTheOnlyTopLevelDestinations() {
        val pages = HomeDjPage.entries
        assertEquals(listOf("Player", "Library", "Setup"), pages.map { it.label })
        assertEquals(pages.size, pages.map { it.label }.distinct().size)
        assertTrue(pages.all { it.label.isNotBlank() })
    }
}
