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
        require(playlistId.length in 2..150 && playlistId.all {
            (it in 'A'..'Z') || (it in 'a'..'z') || (it in '0'..'9') || it == '_' || it == '-'
        }) { "Invalid playlist ID" }
        return arrayOf(
            "--yes-playlist",
            "--ignore-errors",
            "--extract-audio",
            "--audio-format", "mp3",
            "--audio-quality", "5",
            "--no-overwrites",
            "--paths", OUTPUT_DIR,
            "--output", "%(playlist_title).80B/%(playlist_index)03d - %(title).140B [%(id)s].%(ext)s",
            "https://www.youtube.com/playlist?list=$playlistId",
        )
    }

    fun start(context: Context, playlistId: String): Boolean {
        val command = Intent("$PREFIX")
            .setClassName(PACKAGE, SERVICE)
            .putExtra("${PREFIX}_PATH", "/data/data/com.termux/files/usr/bin/yt-dlp")
            .putExtra("${PREFIX}_ARGUMENTS", arguments(playlistId))
            .putExtra("${PREFIX}_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("${PREFIX}_BACKGROUND", false)
        return context.startService(command) != null
    }
}
