package dev.homedj.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Platform lifetime contracts: the JVM cannot emulate Samsung's CPU suspend or boot delivery. */
class RemoteServerLifecycleTest {
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private fun source(name: String) = File(repo, "app/src/main/java/dev/homedj/app/$name.kt").readText()

    @Test fun restartRequiresBothTheSavedServerRoleAndTheExistingOptIn() {
        for (mode in listOf(null, HomeDjMode.CONTROLLER, HomeDjMode.SERVER)) {
            assertFalse(remoteServerAllowed(mode, enabled = false))
            assertEquals(mode == HomeDjMode.SERVER, remoteServerAllowed(mode, enabled = true))
        }
        // A late system restart after a role change or an explicit Off must not resurrect it.
        assertTrue(remoteServerAllowed(HomeDjMode.SERVER, true))
        assertFalse(remoteServerAllowed(HomeDjMode.CONTROLLER, true))
        assertFalse(remoteServerAllowed(HomeDjMode.SERVER, false))
    }

    @Test fun idleListenerKeepsTheCpuAvailableUntilServiceDestruction() {
        val service = source("RemoteServerService")
        assertTrue("A Wi-Fi lock alone cannot keep the CPU servicing inbound commands",
            service.contains("PowerManager.PARTIAL_WAKE_LOCK"))
        val teardown = service.substringAfter("override fun onDestroy()")
        assertTrue("Turning off remote control must release its CPU lock", teardown.contains("wakeLock"))
        assertTrue(teardown.contains("release()"))
    }

    @Test fun rebootAndAppUpdateRestoreTheOptedInListener() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(repo, "app/src/main/AndroidManifest.xml"))
        val receivers = document.getElementsByTagName("receiver")
        val restore = (0 until receivers.length).map { receivers.item(it) as Element }
            .firstOrNull { it.getAttribute("android:name") == ".RemoteServerRestoreReceiver" }
        assertTrue("The listener must have a boot/update restoration entry point", restore != null)
        requireNotNull(restore)
        assertEquals("false", restore.getAttribute("android:exported"))
        val actions = restore.getElementsByTagName("action")
        assertEquals(setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED"),
            (0 until actions.length).map { (actions.item(it) as Element).getAttribute("android:name") }.toSet())
    }
}
