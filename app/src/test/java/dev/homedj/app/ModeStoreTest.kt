package dev.homedj.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModeStoreTest {
    @Test fun persistedRolesHaveStableNames() {
        assertEquals(HomeDjMode.SERVER, parseHomeDjMode("SERVER"))
        assertEquals(HomeDjMode.CONTROLLER, parseHomeDjMode("CONTROLLER"))
    }

    @Test fun unknownRoleNeverDefaultsToServer() {
        assertNull(parseHomeDjMode(null))
        assertNull(parseHomeDjMode(""))
        assertNull(parseHomeDjMode("INVALID"))
        assertNull(parseHomeDjMode("server"))
    }
}
