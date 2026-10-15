package dev.homedj.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Explicit user-initiated Termux handoff. Termux, not HomeDJ, owns process lifetime,
 * downloads, and storage. A successful service start does not mean a download finished.
 */
internal object TermuxPlaylistDownloads {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val PACKAGE = "com.termux"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val PREFIX = "com.termux.RUN_COMMAND"
    private const val OUTPUT_DIR = "/storage/emulated/0/Music/HomeDJ"

    fun validId(id: String): Boolean = id.length in 2..150 && id.all {
        (it in 'A'..'Z') || (it in 'a'..'z') || (it in '0'..'9') || it == '_' || it == '-'
    }

    @Suppress("DEPRECATION")
    fun isInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /** Fixed executable and argv. No shell or untrusted URL is ever executed. */
    fun arguments(playlistId: String): Array<String> {
        require(validId(playlistId)) { "Invalid playlist ID" }
        return arrayOf(
            "--yes-playlist",
            "--ignore-errors",
            "--extract-audio",
            "--audio-format", "mp3",
            "--audio-quality", "5",
            "--no-overwrites",
            "--download-archive", OUTPUT_DIR + "/downloaded-ids.txt",
            "--paths", OUTPUT_DIR,
            "--output", "%(playlist_title).80B/%(playlist_index)03d - %(title).140B [%(id)s].%(ext)s",
            "https://www.youtube.com/playlist?list=$playlistId",
        )
    }

    fun start(context: Context, playlistId: String): Boolean =
        execute(context, arguments(playlistId), background = false)

    /** One yt-dlp invocation, shared archive and fixed argv. Never launches arbitrary shell text. */
    fun startBatch(context: Context, ids: List<String>): Boolean {
        require(ids.isNotEmpty() && ids.size <= 20 && ids.all(::validId))
        val args = arguments(ids.first()).dropLast(1).toTypedArray() +
            ids.map { "https://www.youtube.com/playlist?list=$it" }
        return execute(context, args, background = true)
    }

    fun startStream(context: Context, id: String, result: android.app.PendingIntent): Boolean {
        require(validId(id)) { "Invalid track ID" }
        val args = arrayOf(
            "--no-playlist", "--no-warnings", "--no-progress",
            "--get-url", "-f", "bestaudio",
            "https://www.youtube.com/watch?v=$id",
        )
        return execute(context, args, background = true, callback = result)
    }

    private fun execute(
        context: Context, args: Array<String>, background: Boolean,
        callback: android.app.PendingIntent? = null,
    ): Boolean {
        val command = Intent(PREFIX)
            .setClassName(PACKAGE, SERVICE)
            .putExtra("${PREFIX}_PATH", "/data/data/com.termux/files/usr/bin/yt-dlp")
            .putExtra("${PREFIX}_ARGUMENTS", args)
            .putExtra("${PREFIX}_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("${PREFIX}_BACKGROUND", background)
        if (callback != null) command.putExtra("${PREFIX}_PENDING_INTENT", callback)
        return context.startService(command) != null
    }
}
