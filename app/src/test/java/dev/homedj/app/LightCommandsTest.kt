package dev.homedj.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LightCommandsTest {
    @Test fun acceptsManualCodesWithValidCheckDigit() {
        assertEquals("34970112332", matterSetupCode("34970112332"))
        assertEquals("34970112332", matterSetupCode(" 3497-011-2332 "))
        assertEquals("749701123365521327694", matterSetupCode("749701123365521327694"))
        assertEquals("MT:Y.K9042C00KA0648G00", matterSetupCode("MT:Y.K9042C00KA0648G00"))
    }

    @Test fun rejectsTyposAndNonCodes() {
        assertNull(matterSetupCode("34970112331"))
        assertNull(matterSetupCode("43970112332"))
        assertNull(matterSetupCode("3497011233"))
        assertNull(matterSetupCode("3497O112332"))
        assertNull(matterSetupCode("MT:short"))
        assertNull(matterSetupCode("MT:Y.K9042c00KA0648G00"))
        assertNull(matterSetupCode(""))
    }

    @Test fun mapsColourToMatterRanges() {
        assertEquals(0, matterHue(0))
        assertEquals(0, matterHue(360))
        assertEquals(127, matterHue(180))
        assertEquals(212, matterHue(-60))
        assertEquals(254, matterSaturation(100))
        assertEquals(0, matterSaturation(-5))
        assertEquals(370, mireds(2700))
        assertEquals(154, mireds(6500))
        assertEquals(100, mireds(20_000))
    }

    @Test fun encodesCommandsAsTlvStructures() {
        val on = powerCommand(true)
        assertEquals(MatterIds.ON_OFF to MatterIds.ON, on.cluster to on.command)
        assertArrayEquals(byteArrayOf(0x15, 0x18), on.tlv)
        assertEquals(MatterIds.OFF, powerCommand(false).command)

        val red = colorCommand(LightColor.Hue(0, 100), transitionTenths = 0)
        assertEquals(MatterIds.COLOR_CONTROL to MatterIds.MOVE_TO_HUE_AND_SATURATION, red.cluster to red.command)
        // hue 0, saturation 254, transition 0, ExecuteIfOff mask and override, as 1-byte context-tagged uints.
        assertArrayEquals(byteArrayOf(0x15, 0x24, 0, 0, 0x24, 1, 0xFE.toByte(), 0x24, 2, 0, 0x24, 3, 1, 0x24, 4, 1, 0x18),
            red.tlv)

        val warm = colorCommand(LightColor.White(2700), transitionTenths = 5)
        assertEquals(MatterIds.MOVE_TO_COLOR_TEMPERATURE, warm.command)
        // 370 mireds needs a 2-byte uint (little endian).
        assertArrayEquals(byteArrayOf(0x15, 0x25, 0, 0x72, 0x01, 0x24, 1, 5, 0x24, 2, 1, 0x24, 3, 1, 0x18), warm.tlv)
    }

    @Test fun encodesBrightnessAsMoveToLevelWithOnOff() {
        assertEquals(1, matterLevel(0))
        assertEquals(3, matterLevel(1))
        assertEquals(127, matterLevel(50))
        assertEquals(254, matterLevel(100))
        val half = levelCommand(50)
        assertEquals(MatterIds.LEVEL_CONTROL to MatterIds.MOVE_TO_LEVEL_WITH_ON_OFF, half.cluster to half.command)
        // level 127, transition 0, options mask 0, options override 0.
        assertArrayEquals(byteArrayOf(0x15, 0x24, 0, 127, 0x24, 1, 0, 0x24, 2, 0, 0x24, 3, 0, 0x18), half.tlv)
    }
}
