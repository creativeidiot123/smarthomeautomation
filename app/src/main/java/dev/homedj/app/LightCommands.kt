package dev.homedj.app

import kotlin.math.roundToInt
import matter.tlv.AnonymousTag
import matter.tlv.ContextSpecificTag
import matter.tlv.TlvWriter

/** Matter Application Cluster Library IDs used by Lights (spec 1.4, unchanged since 1.0). */
internal object MatterIds {
    const val ON_OFF = 0x0006L
    const val OFF = 0x00L
    const val ON = 0x01L
    const val LEVEL_CONTROL = 0x0008L
    const val MOVE_TO_LEVEL = 0x00L
    const val MOVE_TO_LEVEL_WITH_ON_OFF = 0x04L
    const val COLOR_CONTROL = 0x0300L
    const val MOVE_TO_HUE_AND_SATURATION = 0x06L
    const val MOVE_TO_COLOR_TEMPERATURE = 0x0AL
}

internal sealed interface LightColor {
    data class Hue(val degrees: Int, val saturationPercent: Int) : LightColor
    data class White(val kelvin: Int) : LightColor
}

internal class MatterCommand(val cluster: Long, val command: Long, val tlv: ByteArray)

/**
 * A Matter onboarding payload as typed or pasted: an 11/21-digit manual code (spaces and dashes
 * allowed, Verhoeff check digit verified) or an `MT:` QR payload. Null when it cannot be one.
 */
internal fun matterSetupCode(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.startsWith("MT:")) {
        return trimmed.takeIf { it.length in 22..512 && it.drop(3).all { char -> char in BASE38 } }
    }
    val digits = trimmed.filterNot { it == ' ' || it == '-' }
    return digits.takeIf { (it.length == 11 || it.length == 21) && it.all(Char::isDigit) && verhoeffValid(it) }
}

/** Matter hue is 0..254 for 0..360°; saturation 0..254 for 0..100 %. */
internal fun matterHue(degrees: Int): Int = (Math.floorMod(degrees, 360) * 254 / 360.0).roundToInt()

internal fun matterSaturation(percent: Int): Int = (percent.coerceIn(0, 100) * 2.54).roundToInt()

internal fun mireds(kelvin: Int): Int = (1_000_000.0 / kelvin.coerceIn(1_000, 10_000)).roundToInt()

/** Matter lighting level is 1..254 (0 is reserved for off); brightness is 1..100 %. */
internal fun matterLevel(percent: Int): Int = (percent * 2.54).roundToInt().coerceIn(1, 254)

internal fun powerCommand(on: Boolean): MatterCommand =
    MatterCommand(MatterIds.ON_OFF, if (on) MatterIds.ON else MatterIds.OFF,
        TlvWriter().startStructure(AnonymousTag).endStructure().getEncoded())

/**
 * Bulb-native transition; no phone-side animation loop or timer. Zero fades to off. Without
 * [withOnOff] (MoveToLevel) the power never changes; [executeIfOff] then decides whether an off
 * bulb still takes the level (OptionsMask bit 0 set, OptionsOverride bit 0 as given).
 */
internal fun levelCommand(percent: Int, transitionTenths: Int = 0, withOnOff: Boolean = true,
                          executeIfOff: Boolean = false): MatterCommand =
    MatterCommand(MatterIds.LEVEL_CONTROL, if (withOnOff) MatterIds.MOVE_TO_LEVEL_WITH_ON_OFF else MatterIds.MOVE_TO_LEVEL,
        TlvWriter().startStructure(AnonymousTag)
            .put(ContextSpecificTag(0), if (percent == 0) 0u else matterLevel(percent).toUInt())
            .put(ContextSpecificTag(1), transitionTenths.toUInt())
            .put(ContextSpecificTag(2), if (withOnOff) 0u else 1u)
            .put(ContextSpecificTag(3), if (!withOnOff && executeIfOff) 1u else 0u)
            .endStructure().getEncoded())

/**
 * Colour change; by default with ExecuteIfOff set (OptionsMask/OptionsOverride bit 0), so a bulb
 * that is off still takes the colour instead of silently ignoring it. Variation steps clear it.
 */
internal fun colorCommand(color: LightColor, transitionTenths: Int, executeIfOff: Boolean = true): MatterCommand {
    val override = if (executeIfOff) 1u else 0u
    val writer = TlvWriter().startStructure(AnonymousTag)
    val command = when (color) {
        is LightColor.Hue -> {
            writer.put(ContextSpecificTag(0), matterHue(color.degrees).toUInt())
                .put(ContextSpecificTag(1), matterSaturation(color.saturationPercent).toUInt())
                .put(ContextSpecificTag(2), transitionTenths.toUInt())
                .put(ContextSpecificTag(3), 1u)
                .put(ContextSpecificTag(4), override)
            MatterIds.MOVE_TO_HUE_AND_SATURATION
        }
        is LightColor.White -> {
            writer.put(ContextSpecificTag(0), mireds(color.kelvin).toUInt())
                .put(ContextSpecificTag(1), transitionTenths.toUInt())
                .put(ContextSpecificTag(2), 1u)
                .put(ContextSpecificTag(3), override)
            MatterIds.MOVE_TO_COLOR_TEMPERATURE
        }
    }
    return MatterCommand(MatterIds.COLOR_CONTROL, command, writer.endStructure().getEncoded())
}

private const val BASE38 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-."

private val VERHOEFF_D = arrayOf(
    intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
    intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6), intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
    intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8), intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
    intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2), intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
    intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4), intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
)
private val VERHOEFF_P = arrayOf(
    intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
    intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2), intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
    intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0), intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
    intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5), intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
)

/** Matter manual pairing codes end in a Verhoeff check digit, so a typo fails here, not after discovery. */
private fun verhoeffValid(digits: String): Boolean {
    var check = 0
    digits.reversed().forEachIndexed { index, char -> check = VERHOEFF_D[check][VERHOEFF_P[index % 8][char - '0']] }
    return check == 0
}
