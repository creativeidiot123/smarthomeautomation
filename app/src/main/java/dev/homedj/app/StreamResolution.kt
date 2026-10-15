package dev.homedj.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.net.URI
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.util.UUID

/**
 * Termux resolves a transient direct audio URL. Media3, in PlaybackService, remains the sole
 * playback owner. No Google access token or user cookies are sent to Termux.
 */
internal object StreamResolution {
    const val ACTION_RESULT = "dev.homedj.app.STREAM_RESOLVED"
    private const val EXTRA_REQUEST = "stream_request"
    private const val MAX_AGE = 10 * 60 * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("homedj_stream_request", Context.MODE_PRIVATE)

    fun request(context: Context, song: YouTubeTrack): Boolean {
        require(TermuxPlaylistDownloads.validId(song.id)) { "Invalid YouTube track" }
        val nonce = UUID.randomUUID().toString()
        val request = Intent(context, PlaybackService::class.java).apply {
            action = ACTION_RESULT
            putExtra(EXTRA_REQUEST, nonce)
        }
        val pending = PendingIntent.getService(
            context, nonce.hashCode(), request,
            PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        )
        prefs(context).edit()
            .putString("nonce", nonce)
            .putString("title", song.title.take(300))
            .putLong("issued", System.currentTimeMillis())
            .commit()
        return try {
            val sent = TermuxPlaylistDownloads.startStream(context, song.id, pending)
            if (!sent) cancel(context)
            sent
        } catch (failure: Exception) {
            cancel(context)
            Log.w("HomeDJStream", "Could not start Termux resolver: " + failure.javaClass.simpleName)
            false
        }
    }

    /** Reject old callbacks and consume the request exactly once, even on failure. */
    fun consume(context: Context, intent: Intent): String? {
        val saved = prefs(context)
        val title = saved.getString("title", null)
        val nonce = saved.getString("nonce", null)
        val issued = saved.getLong("issued", 0)
        if (nonce.isNullOrBlank() || nonce != intent.getStringExtra(EXTRA_REQUEST)) return null
        cancel(context)
        if (System.currentTimeMillis() - issued !in 0..MAX_AGE) return null
        return title
    }

    fun cancel(context: Context) {
        prefs(context).edit().clear().commit()
    }

    /** Fail closed on yt-dlp errors, unexpected schemes and non-YouTube audio hosts. */
    fun playableUrl(result: Bundle?): String? {
        if (result == null || result.getInt("err", -1) != -1 ||
            result.getInt("exitCode", -1) != 0) return null
        val stdout = result.getString("stdout")?.trim().orEmpty()
        if (stdout.length > 16000 || stdout.lines().size != 1) return null
        val uri = try { URI(stdout) } catch (_: java.net.URISyntaxException) { return null }
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme != "https" || uri.userInfo != null ||
            !(host == "googlevideo.com" || host.endsWith(".googlevideo.com"))) return null
        return stdout
    }
}
