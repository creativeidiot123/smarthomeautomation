package dev.homedj.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Samsung's one silent download notification: "3 of 12 done" while saves are queued or running,
 * then how many were downloaded once none are left. Workers update it as they start and end and
 * user stops update it with the songs they stopped, so it follows WorkManager, not a screen.
 */
internal object DownloadNotice {
    private const val CHANNEL = "downloads"
    private const val NOTIFICATION_ID = 43
    private val lock = Mutex()
    private var run = DownloadRun()
    /** `elapsedRealtime` of the last "Downloading" post; null after a finished notice or none. */
    private var progressPostedAt: Long? = null

    /**
     * A worker starting [id]. Songs this run already counts change nothing, so a big playlist's
     * workers do not each query WorkManager and repost while holding the lock in turn.
     */
    suspend fun started(context: Context, id: String) {
        if (lock.withLock { run.left > 0 && id in run.songs }) return
        update(context)
    }

    /** [ended]: songs whose save just finished, failed for good or was stopped. */
    suspend fun update(context: Context, ended: Collection<String> = emptyList()) {
        val app = context.applicationContext
        try {
            lock.withLock {
                val pending = OfflineAudioLibrary.pendingIds(app).first() - ended.toSet()
                val saved = withContext(Dispatchers.IO) {
                    ended.filterTo(hashSetOf()) { OfflineAudioLibrary.existing(app, it) != null }
                }
                run = run.next(pending, saved)
                show(app, run)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJOffline", "Download notification failed: " + failure.javaClass.simpleName)
        }
    }

    private fun show(app: Context, run: DownloadRun) {
        if (run.songs.isEmpty()) return
        val manager = app.getSystemService(NotificationManager::class.java)
        val notice = run.notice()
        if (notice == null) {
            manager.cancel(NOTIFICATION_ID)
            progressPostedAt = null
            return
        }
        val (title, text) = notice
        val now = SystemClock.elapsedRealtime()
        if (!postDue(run.left > 0, progressPostedAt, now)) return
        progressPostedAt = if (run.left > 0) now else null
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "HomeDJ downloads", NotificationManager.IMPORTANCE_LOW)
        )
        val open = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.let { PendingIntent.getActivity(app, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val downloading = run.left > 0
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(if (downloading) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!downloading)
            .also { if (downloading) it.setProgress(run.songs.size, run.songs.size - run.left, false) }
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private const val PROGRESS_INTERVAL_MILLIS = 1_000L

    /**
     * Android sheds a package's updates above about 5 a second, so a burst could drop the final
     * notice. "Downloading" counts post at most once a second; the first one and a finished notice
     * always post. A skipped count shows on the next song that ends.
     */
    internal fun postDue(downloading: Boolean, progressPostedAt: Long?, now: Long): Boolean =
        !downloading || progressPostedAt == null || now - progressPostedAt >= PROGRESS_INTERVAL_MILLIS
}

/**
 * One run of downloads: every song seen pending since none were, which of them were saved, and how
 * many are still pending. A run with nothing left is over; the next update starts a new one.
 */
internal data class DownloadRun(
    val songs: Set<String> = emptySet(),
    val saved: Set<String> = emptySet(),
    val left: Int = 0,
) {
    /** [savedNow]: ended songs that have a file. Only songs of this run count, not stops of older saves. */
    fun next(pending: Set<String>, savedNow: Set<String>): DownloadRun {
        val base = if (left == 0) DownloadRun() else this
        return DownloadRun(base.songs + pending, base.saved + savedNow.filter { it in base.songs }, pending.size)
    }

    /** Title and text while songs are left or once some were saved; null hides the notification. */
    fun notice(): Pair<String, String>? = when {
        left > 0 -> "Downloading songs" to "${songs.size - left} of ${songs.size} done"
        saved.isNotEmpty() -> "Downloads finished" to songCount(saved.size) +
            if (songs.size > saved.size) " • ${songs.size - saved.size} not downloaded" else ""
        else -> null
    }
}
