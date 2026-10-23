package dev.homedj.app

import android.content.Context
import android.util.Log
import java.io.IOException
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Whole-playlist downloads larger than one batch. "Download" or "Download the rest" on either
 * phone queues up to [BATCH] missing songs (failed ones again) and remembers the playlist; as
 * saves end, [PlaylistDownloadWorker] tops the queue back up to [BATCH] pending songs until every
 * song of the playlist is downloaded or failed for good, then forgets it. Stopping or removing
 * any of its songs forgets it too, so a later top-up never undoes the stop.
 * Device-local SharedPreferences `homedj_playlist_downloads`, key `playlists`.
 */
internal object PlaylistDownloads {
    const val BATCH = 250
    private const val WORK_NAME = "homedj_playlist_topup"
    private val lock = Mutex()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("homedj_playlist_downloads", Context.MODE_PRIVATE)

    private fun requested(context: Context): Set<String> =
        prefs(context).getStringSet("playlists", emptySet()).orEmpty().toSet()

    private suspend fun write(context: Context, ids: Set<String>) = withContext(Dispatchers.IO) {
        if (!prefs(context).edit().putStringSet("playlists", ids).commit()) {
            throw IOException("Could not remember playlist downloads")
        }
    }

    /** [queued] songs started now; [later] are left for the top-ups. */
    class Started(val queued: Int, val later: Int)

    /** An explicit tap: up to [BATCH] of this playlist's missing songs now, the rest remembered. */
    suspend fun start(context: Context, playlistId: String, tracks: List<YouTubeTrack>): Started {
        val app = context.applicationContext
        return lock.withLock {
            val pending = OfflineAudioLibrary.pendingIds(app).first()
            val saved = OfflineAudioLibrary.savedIds(app)
            val missing = nextSongs(tracks, pending, emptySet(), saved::contains).orEmpty()
            queueBatch(missing, remember = {
                val ids = requested(app)
                // Even a single batch can be interrupted before all its jobs reach WorkManager.
                write(app, if (missing.isNotEmpty()) ids + playlistId else ids - playlistId)
            }, enqueue = { OfflineAudioLibrary.enqueue(app, it) })
        }
    }

    /** Persist intent before the first enqueue: a crash can then lose no unqueued tail. */
    internal suspend fun queueBatch(
        missing: List<YouTubeTrack>, remember: suspend () -> Unit, enqueue: (YouTubeTrack) -> Boolean,
    ): Started {
        remember()
        val queued = withContext(Dispatchers.IO) { missing.take(BATCH).count(enqueue) }
        return Started(queued, (missing.size - BATCH).coerceAtLeast(0))
    }

    /** A save ended: let a single top-up run when a playlist still waits. Cheap when none does. */
    fun continueLater(context: Context) {
        val app = context.applicationContext
        if (requested(app).isEmpty()) return
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PlaylistDownloadWorker>().build())
    }

    /** Stops or removals of [ids] end the whole-playlist downloads that list any of them. */
    suspend fun forget(context: Context, ids: Collection<String>) {
        val app = context.applicationContext
        if (requested(app).isEmpty() || ids.isEmpty()) return
        val catalog = SavedPlaylistStore.shared(app).read()
        val stopped = ids.toSet()
        lock.withLock {
            val waiting = requested(app)
            write(app, waiting.filterNot { id -> catalog.tracks[id].orEmpty().any { it.id in stopped } }.toSet())
        }
    }

    /** Queues the next songs of waiting playlists while fewer than [BATCH] saves are pending. */
    suspend fun topUp(context: Context) {
        val app = context.applicationContext
        if (requested(app).isEmpty() || ModeStore(app).mode.first() != HomeDjMode.SERVER) return
        lock.withLock {
            val catalog = SavedPlaylistStore.shared(app).read()
            val pending = OfflineAudioLibrary.pendingIds(app).first().toHashSet()
            val failed = OfflineAudioLibrary.failedIds(app).first()
            val saved = OfflineAudioLibrary.savedIds(app)
            val finished = hashSetOf<String>()
            for (playlistId in requested(app)) {
                val left = nextSongs(catalog.tracks[playlistId], pending, failed, saved::contains)
                if (left == null) {
                    finished += playlistId
                    continue
                }
                val room = BATCH - pending.size
                if (room <= 0) continue
                withContext(Dispatchers.IO) {
                    left.take(room).forEach { if (OfflineAudioLibrary.enqueue(app, it)) pending += it.id }
                }
            }
            if (finished.isNotEmpty()) write(app, requested(app) - finished)
        }
    }

    /**
     * Songs of a waiting playlist still to queue: not downloaded, pending or failed for good.
     * Null when nothing is left (or the playlist is gone), so the playlist is done.
     */
    internal fun nextSongs(
        tracks: List<YouTubeTrack>?, pending: Set<String>, failed: Set<String>, saved: (String) -> Boolean,
    ): List<YouTubeTrack>? = tracks.orEmpty()
        .filter { NativeAudioSource.validId(it.id) }.distinctBy(YouTubeTrack::id)
        .filter { it.id !in pending && it.id !in failed && !saved(it.id) }
        .takeIf { it.isNotEmpty() }
}

/** " The other 350 follow automatically." after a batch, else nothing. */
internal fun batchNote(later: Int): String =
    if (later <= 0) "" else if (later == 1) " 1 more follows automatically." else " The other $later follow automatically."

/** The single-flight top-up of [PlaylistDownloads]; idempotent, so a stop or retry loses nothing. */
class PlaylistDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            PlaylistDownloads.topUp(applicationContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The next save that ends, or the hourly sync, tops up again.
            Log.w("HomeDJOffline", "Playlist download top-up failed: " + failure.javaClass.simpleName)
        }
        return Result.success()
    }
}
