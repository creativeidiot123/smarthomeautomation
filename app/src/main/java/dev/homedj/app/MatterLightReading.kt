package dev.homedj.app

import chip.devicecontroller.ChipDeviceController
import chip.devicecontroller.ReportCallback
import chip.devicecontroller.model.ChipAttributePath
import chip.devicecontroller.model.ChipEventPath
import chip.devicecontroller.model.NodeState
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine
import matter.tlv.AnonymousTag
import matter.tlv.TlvReader
import org.json.JSONObject

/** A dated observation, never a promise that the bulb is still reachable. Null capability = unknown. */
internal data class LightReading(val at: Long, val dimmable: Boolean?, val color: Boolean?, val white: Boolean?) {
    fun json() = JSONObject().put("at", at).also { json ->
        dimmable?.let { json.put("dimmable", it) }
        color?.let { json.put("color", it) }
        white?.let { json.put("white", it) }
    }
    companion object {
        fun read(json: JSONObject): LightReading? = LightReading(json.optLong("at"),
            if (json.has("dimmable")) json.optBoolean("dimmable") else null,
            if (json.has("color")) json.optBoolean("color") else null,
            if (json.has("white")) json.optBoolean("white") else null).takeIf { it.at > 0 }
    }
}

/** Exact SDK v1.6.1.0 readAttributePath/ReportCallback boundary. No lasting subscription. */
internal suspend fun readMatterLight(chip: ChipDeviceController, device: Long): Map<Pair<Long, Long>, ByteArray> =
    suspendCancellableCoroutine { continuation ->
        val values = mutableMapOf<Pair<Long, Long>, ByteArray>()
        val paths = listOf(6L to 0L, 8L to 0L, 8L to 0xFFFCL, 0x300L to 0L, 0x300L to 1L,
            0x300L to 7L, 0x300L to 8L, 0x300L to 0xFFFCL)
        chip.readAttributePath(object : ReportCallback {
            override fun onError(attributePath: ChipAttributePath?, eventPath: ChipEventPath?, e: Exception) {
                // An unsupported optional attribute does not invalidate the required power read.
                if (attributePath == null && continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onReport(nodeState: NodeState) {
                val endpoint = nodeState.getEndpointState(1) ?: return
                paths.forEach { (cluster, attribute) ->
                    endpoint.getClusterState(cluster)?.getAttributeState(attribute)?.tlv?.let { values[cluster to attribute] = it }
                }
            }
            override fun onDone() {
                if (continuation.isActive) continuation.resume(values.toMap())
            }
        }, device, paths.map { (cluster, attribute) -> ChipAttributePath.newInstance(1, cluster, attribute) }, 5_000)
    }

/** Invalid optional TLV is ignored; a missing/invalid OnOff value fails the read. */
internal fun lightFromReading(previous: Bulb, values: Map<Pair<Long, Long>, ByteArray>, at: Long): Bulb {
    fun number(cluster: Long, attribute: Long): Int? = values[cluster to attribute]?.let { bytes ->
        runCatching { TlvReader(bytes).getUInt(AnonymousTag).toInt() }.getOrNull()
    }
    val power = values[6L to 0L]?.let { bytes -> runCatching { TlvReader(bytes).getBoolean(AnonymousTag) }.getOrNull() }
        ?: throw java.io.IOException("Light state unavailable")
    val features = number(0x300, 0xFFFC)
    val rawLevel = number(8, 0)?.takeIf { it in 0..254 }
    val colorMode = number(0x300, 8)
    val hue = number(0x300, 0)?.takeIf { it in 0..254 }
    val sat = number(0x300, 1)?.takeIf { it in 0..254 }
    val mired = number(0x300, 7)?.takeIf { it > 0 }
    val color = when {
        colorMode == 0 && hue != null && sat != null -> LightColor.Hue((hue * 360.0 / 254).roundToInt().coerceAtMost(359),
            (sat / 2.54).roundToInt().coerceIn(0, 100))
        colorMode == 2 && mired != null -> LightColor.White((1_000_000.0 / mired).roundToInt()).takeIf(LightsPolicy::validColor)
        else -> null
    }
    return previous.copy(on = power, level = rawLevel?.takeIf { it > 0 }?.let { (it / 2.54).roundToInt().coerceIn(1, 100) } ?: previous.level,
        color = color ?: previous.color, reading = LightReading(at, number(8, 0xFFFC)?.let { true },
            features?.let { it and 1 != 0 }, features?.let { it and 16 != 0 }))
}
