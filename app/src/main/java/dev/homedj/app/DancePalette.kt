package dev.homedj.app

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The song's own colours for the light show: up to four distinct vivid hues (degrees) from its
 * artwork, strongest first. Greys, darks and washed-out pixels are ignored; one dominant hue gets
 * two neighbours so the lights still move. Empty when the artwork has no real colour.
 */
internal fun artworkHues(pixels: IntArray, max: Int = 4): List<Int> {
    val weight = DoubleArray(BINS)
    val x = DoubleArray(BINS)
    val y = DoubleArray(BINS)
    var vivid = 0
    pixels.forEach { pixel ->
        val r = (pixel shr 16 and 255) / 255.0
        val g = (pixel shr 8 and 255) / 255.0
        val b = (pixel and 255) / 255.0
        val high = maxOf(r, g, b)
        val chroma = high - minOf(r, g, b)
        val saturation = if (high == 0.0) 0.0 else chroma / high
        if (high < 0.2 || saturation < 0.25) return@forEach
        val hue = when (high) {
            r -> (60 * (g - b) / chroma).let { if (it < 0) it + 360 else it }
            g -> 60 * ((b - r) / chroma + 2)
            else -> 60 * ((r - g) / chroma + 4)
        }
        val w = saturation * saturation * high
        val bin = (hue / (360.0 / BINS)).toInt().coerceIn(0, BINS - 1)
        weight[bin] += w
        x[bin] += w * cos(Math.toRadians(hue))
        y[bin] += w * sin(Math.toRadians(hue))
        vivid++
    }
    if (vivid < pixels.size * 0.03) return emptyList()
    val smoothed = DoubleArray(BINS) { weight[it] + 0.5 * (weight[(it + BINS - 1) % BINS] + weight[(it + 1) % BINS]) }
    val top = smoothed.max()
    val picked = mutableListOf<Int>()
    smoothed.indices.sortedByDescending { smoothed[it] }.forEach { bin ->
        if (picked.size >= max || smoothed[bin] < top * 0.08 || weight[bin] <= 0) return@forEach
        val hue = Math.floorMod(Math.toDegrees(atan2(y[bin], x[bin])).toInt(), 360)
        if (picked.all { hueDistance(it, hue) >= 40 }) picked += hue
    }
    if (picked.size == 1) picked += listOf(Math.floorMod(picked[0] + 40, 360), Math.floorMod(picked[0] - 40, 360))
    return picked
}

private fun hueDistance(a: Int, b: Int): Int = Math.floorMod(a - b, 360).let { minOf(it, 360 - it) }

private const val BINS = 24

/** Loads the song's thumbnail through the shared artwork cache, small and in software, and reads its hues. */
internal suspend fun loadArtworkHues(context: Context, url: String): List<Int> {
    configureMusicImageLoader() // The Samsung may run this before any screen set the shared loader up.
    val app = context.applicationContext
    val request = ImageRequest.Builder(app).data(url).size(48).allowHardware(false).build()
    val bitmap = (SingletonImageLoader.get(app).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return emptyList()
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return artworkHues(pixels)
}
