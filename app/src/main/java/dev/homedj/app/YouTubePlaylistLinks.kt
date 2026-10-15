package dev.homedj.app

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** A pasted URL identifies a playlist, never an arbitrary network host. */
internal object YouTubePlaylistLinks {
    fun idFromLink(text: String): String? {
        if (text.length > 2048) return null
        val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.rawUserInfo != null || uri.port != -1) {
            return null
        }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")) {
            return null
        }
        if (uri.path !in setOf("/playlist", "/watch")) return null
        val rawId = uri.rawQuery?.split('&')?.firstNotNullOfOrNull { parameter ->
            parameter.takeIf { it.substringBefore('=') == "list" }?.substringAfter('=', "")
        } ?: return null
        val id = runCatching {
            URLDecoder.decode(rawId, StandardCharsets.UTF_8.name())
        }.getOrNull() ?: return null
        return id.takeIf { value ->
            value.length in 2..150 &&
                value.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }
        }
    }
}
