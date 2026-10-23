package dev.homedj.app

import android.content.Context
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Backups for failed saves, without WorkManager backoff holding up the rest of a run.
 *
 * In the attempt ([step]): a broken chunk resumes the kept partial straight away (yt-dlp's fragment
 * retries); a rejected link or one that keeps breaking is extracted once more (NewPipe's
 * DownloadMissionRecover, Metrolist's link refresh); after that the next offered stream is tried.
 *
 * Later ([requeue]): a manual save that still failed in a way time may fix (bot check, network,
 * extractor) gets one more Wi-Fi try on the hourly sync; that backup failing again stays failed.
 * Automatic saves need none: the sync queues missing auto-save songs every pass anyway.
 * Device-local SharedPreferences `homedj_download_backup`, key `later` (video IDs awaiting it).
 */
internal object DownloadBackup {
    /** Optional work input; absent (older builds' work) means an ordinary save. */
    const val INPUT_BACKUP = "backup"
    /** Immediate resumes of one link without new bytes before it is extracted again or dropped. */
    const val MAX_RESUMES = 3
    private const val MAX_LATER = MAX_PLAYLIST_SONGS
    private val lock = Mutex()

    enum class Step { RESUME, REFRESH, NEXT_STREAM, GIVE_UP }

    /** A 4xx means this link is refused (expired signature, PoToken); resuming it will not help. */
    internal fun transient(failure: IOException): Boolean = !(failure is AudioHttpStatus && failure.status in 400..499)

    internal fun step(failure: IOException, resumes: Int, refreshed: Boolean, hasNextStream: Boolean): Step = when {
        transient(failure) && resumes < MAX_RESUMES -> Step.RESUME
        !refreshed -> Step.REFRESH
        hasNextStream -> Step.NEXT_STREAM
        else -> Step.GIVE_UP
    }

    /** YouTube refusing the song, or a stop the user or device chose, will not pass with time. */
    internal fun laterMayHelp(failure: Exception): Boolean =
        failure is BotCheckException || failure !is TrackUnavailableException && failure !is DownloadRejected

    /** Only a manual save's first failure earns the later try; a backup that fails stays failed. */
    internal fun wantsLater(failure: Exception, automatic: Boolean, backup: Boolean): Boolean =
        !automatic && !backup && laterMayHelp(failure)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("homedj_download_backup", Context.MODE_PRIVATE)

    private fun read(context: Context): Set<String> =
        prefs(context).getStringSet("later", emptySet()).orEmpty().toSet()

    private fun write(context: Context, ids: Set<String>) {
        if (!prefs(context).edit().putStringSet("later", ids).commit()) throw IOException("Could not store download backups")
    }

    /** Called by a manual save's worker when it fails for good; never throws into the worker. */
    suspend fun remember(context: Context, id: String) {
        try {
            lock.withLock {
                withContext(Dispatchers.IO) {
                    val waiting = read(context)
                    if (waiting.size < MAX_LATER) write(context, waiting + id)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJOffline", "Download backup not stored: " + failure.javaClass.simpleName)
        }
    }

    /** Stopping or removing songs drops their backups, so the later pass never undoes a stop. */
    suspend fun forget(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                val waiting = read(context)
                if (waiting.any { it in ids }) write(context, waiting - ids.toSet())
            }
        }
    }

    /**
     * The hourly sync's pass in Server mode: queues up to [budget] waiting songs once, as Wi-Fi
     * manual saves marked [INPUT_BACKUP]. Returns how many were queued.
     */
    suspend fun requeue(context: Context, budget: Int): Int {
        val app = context.applicationContext
        if (budget <= 0 || ModeStore(app).mode.first() != HomeDjMode.SERVER) return 0
        return lock.withLock {
            val waiting = withContext(Dispatchers.IO) { read(app) }
            if (waiting.isEmpty()) return@withLock 0
            val songs = SavedPlaylistStore.shared(app).read().songs()
            val pending = OfflineAudioLibrary.pendingIds(app).first()
            val now = waiting.take(budget)
            withContext(Dispatchers.IO) {
                // Forgotten before queuing: an interruption loses a backup rather than repeating one.
                write(app, waiting - now.toSet())
                now.filter { it !in pending && NativeAudioSource.validId(it) }.count {
                    OfflineAudioLibrary.enqueue(app, songs[it] ?: YouTubeTrack(it, it), backup = true)
                }
            }
        }
    }
}
