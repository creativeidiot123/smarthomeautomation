package dev.homedj.app

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * App-private durable music, keyed by stable YouTube video ID rather than transient signed URL.
 * The user retains downloads across metadata refreshes; no storage permission or Termux needed.
 */
internal object OfflineAudioLibrary {
    private const val MAX_TRACK_BYTES = 100L * 1024 * 1024
    private const val MAX_LIBRARY_BYTES = 2L * 1024 * 1024 * 1024
    private val extensions = listOf("m4a", "webm", "opus")
    private val commitMutex = Mutex()
    internal const val MAX_PARALLEL_TRANSFERS = 3
    private val transfers = Semaphore(MAX_PARALLEL_TRANSFERS)
    private const val SAVE_TAG = "homedj_offline"
    private const val ID_TAG_PREFIX = "homedj_offline_id_"

    fun directory(context: Context): File = File(context.applicationContext.filesDir, "offline_audio")
    fun existing(context: Context, videoId: String): File? {
        if (!NativeAudioSource.validId(videoId)) return null
        val folder = directory(context)
        return extensions.asSequence()
            .map { File(folder, "$videoId.$it") }
            .firstOrNull { it.isFile && it.length() > 0 }
    }

    fun localMedia(context: Context, track: YouTubeTrack): LocalAudio? =
        existing(context, track.id)?.let { LocalAudio(Uri.fromFile(it), track.title) }

    /**
     * Every downloaded song, newest first, with the details saved beside it, else the catalog's,
     * so queues and the Downloaded view show titles rather than video IDs.
     */
    suspend fun savedTracks(context: Context): List<YouTubeTrack> {
        val catalog = SavedPlaylistStore.shared(context).read().songs()
        return withContext(Dispatchers.IO) {
            directory(context).listFiles().orEmpty()
                .filter { it.isFile && it.length() > 0 && extensions.contains(it.extension) &&
                    NativeAudioSource.validId(it.nameWithoutExtension) }
                .sortedByDescending { it.lastModified() }
                .map { file ->
                    val id = file.nameWithoutExtension
                    offlineTrack(id, OfflineSongInfo.read(context, id), catalog)
                }
        }
    }

    /** Saved details win; a song no saved playlist lists any more keeps its video ID as its name. */
    internal fun offlineTrack(videoId: String, saved: YouTubeTrack?, catalog: Map<String, YouTubeTrack>): YouTubeTrack =
        saved ?: catalog[videoId] ?: YouTubeTrack(videoId, videoId)

    /**
     * Stops queued or running saves of these songs, manual and automatic. A running worker drops its
     * partial file; automatic saves come back on the next sync while their playlist stays opted in.
     */
    fun cancel(context: Context, ids: Collection<String>) {
        val work = WorkManager.getInstance(context.applicationContext)
        ids.filter(NativeAudioSource::validId).forEach { id ->
            work.cancelUniqueWork(workName(id, automatic = false))
            work.cancelUniqueWork(workName(id, automatic = true))
        }
    }

    /** Removing a playlist's download also stops auto-save from downloading it again on the next sync. */
    suspend fun removePlaylist(context: Context, playlistId: String, ids: Collection<String>): Int {
        val auto = AutoLibraryConfig(context)
        if (playlistId in auto.playlistIds) auto.togglePlaylist(playlistId)
        return remove(context, ids)
    }

    /**
     * Explicit user removal: cancels pending saves, then deletes audio, partials, details and art.
     * Holding [commitMutex] keeps a finishing save from committing a file in the middle of it.
     * Returns how many songs had a downloaded file.
     */
    suspend fun remove(context: Context, ids: Collection<String>): Int {
        val valid = ids.filter(NativeAudioSource::validId).toSet()
        cancel(context, valid)
        return withContext(Dispatchers.IO) {
            commitMutex.withLock {
                valid.count { id ->
                    val file = existing(context, id)
                    file?.delete()
                    extensions.forEach { File(directory(context), "$id.$it").delete() }
                    discardPartials(context, id, automatic = false)
                    discardPartials(context, id, automatic = true)
                    OfflineSongInfo.delete(context, id)
                    file != null
                }
            }
        }
    }

    internal fun networkForDownload(accountKey: String?, playlistId: String?): NetworkType =
        if (accountKey != null && playlistId != null) NetworkType.UNMETERED else NetworkType.CONNECTED

    /**
     * Manual and automatic saves use separate unique work and partial files, so a pending
     * Wi-Fi-only auto job whose opt-in was withdrawn never swallows an explicit manual save.
     */
    internal fun workName(id: String, automatic: Boolean): String =
        (if (automatic) "homedj_auto_audio_" else "homedj_audio_") + id

    /** Keyed by stream itag: a resumed attempt only appends to bytes of the same stream format. */
    internal fun partialFile(context: Context, id: String, automatic: Boolean, itag: Int): File =
        File(directory(context), if (automatic) "$id.$itag.auto.part" else "$id.$itag.part")

    internal fun isPartialOf(name: String, id: String, automatic: Boolean): Boolean =
        Regex(Regex.escape(id) + (if (automatic) """\.\d+\.auto\.part""" else """\.\d+\.part"""))
            .matches(name)

    fun discardPartials(context: Context, id: String, automatic: Boolean) {
        directory(context).listFiles().orEmpty()
            .filter { isPartialOf(it.name, id, automatic) }
            .forEach { it.delete() }
    }

    fun enqueue(
        context: Context, track: YouTubeTrack, accountKey: String? = null,
        playlistId: String? = null,
    ) {
        require(NativeAudioSource.validId(track.id)) { "Invalid video ID" }
        // Song details are optional input: work queued by older builds lacks them and uses the catalog.
        val params = Data.Builder()
            .putString("videoId", track.id)
            .putString("accountKey", accountKey)
            .putString("playlistId", playlistId)
            .putString("title", track.title.take(300))
            .putString("artist", track.artist.take(200))
            .putInt("duration", track.durationSeconds)
            .build()
        val automatic = accountKey != null && playlistId != null
        val request = OneTimeWorkRequestBuilder<OfflineAudioWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(
                networkForDownload(accountKey, playlistId)
            ).build())
            .setInputData(params)
            .addTag(SAVE_TAG)
            .addTag(ID_TAG_PREFIX + track.id)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName(track.id, automatic), ExistingWorkPolicy.KEEP, request,
        )
    }

    /**
     * Video IDs with a manual or automatic save still queued or running, updated whenever WorkManager
     * changes. Saves queued by a build without tags are not listed until they finish.
     */
    fun pendingIds(context: Context): Flow<Set<String>> =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosFlow(WorkQuery.Builder.fromTags(listOf(SAVE_TAG))
                .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED))
                .build())
            .map { infos -> idsFromTags(infos.map(WorkInfo::tags)) }
            .distinctUntilChanged()

    internal fun idsFromTags(tagSets: List<Set<String>>): Set<String> =
        tagSets.flatMapTo(hashSetOf()) { tags ->
            tags.mapNotNull { tag -> tag.removePrefix(ID_TAG_PREFIX).takeIf { tag.startsWith(ID_TAG_PREFIX) } }
        }

    internal suspend fun download(
        context: Context, id: String, automatic: Boolean, authorized: suspend () -> Boolean,
    ): File = limitedTransfer {
        withContext(Dispatchers.IO) {
            require(NativeAudioSource.validId(id))
            existing(context, id)?.let { return@withContext it }
            if (!authorized()) throw DownloadRejected("Download no longer authorized")
            val link = NativeAudioSource.resolve(id)
            val folder = directory(context)
            if (!folder.exists() && !folder.mkdirs()) throw IOException("Cannot create audio directory")
            val destination = File(folder, "$id.${link.extension}")
            val partial = partialFile(context, id, automatic, link.itag)
            // A partial for another stream format cannot be resumed with this link.
            folder.listFiles().orEmpty()
                .filter { isPartialOf(it.name, id, automatic) && it != partial }
                .forEach { it.delete() }
            val otherBytes = folder.listFiles().orEmpty().filter { it.isFile && it != partial }
                .sumOf { it.length().coerceAtLeast(0) }
            if (otherBytes >= MAX_LIBRARY_BYTES) throw DownloadRejected("Offline library storage limit reached")
            try {
                AudioTransfer.fetch(link.url, partial) { total ->
                    if (!authorized()) throw DownloadRejected("Download stopped: server mode or consent changed")
                    if (folder.usableSpace < 10L * 1024 * 1024) throw DownloadRejected("Device storage nearly full")
                    if (total > MAX_TRACK_BYTES || total + otherBytes > MAX_LIBRARY_BYTES) {
                        throw DownloadRejected("Audio exceeds offline storage budget")
                    }
                }
                coroutineContext.ensureActive()
                commitMutex.withLock {
                    // A removal may have cancelled this save while it waited for the lock.
                    coroutineContext.ensureActive()
                    if (!authorized()) throw DownloadRejected("Download no longer authorized")
                    val committedBytes = folder.listFiles().orEmpty()
                        .filter { it.isFile && it.extension in extensions }
                        .sumOf { it.length().coerceAtLeast(0) }
                    if (committedBytes + partial.length() > MAX_LIBRARY_BYTES) {
                        throw DownloadRejected("Offline library size limit reached")
                    }
                    if (!partial.renameTo(destination)) throw DownloadRejected("Unable to commit downloaded audio")
                }
                destination
            } catch (rejected: DownloadRejected) {
                // Any other failure keeps the partial so the next worker attempt resumes it.
                partial.delete()
                throw rejected
            }
        }
    }

    /**
     * WorkManager starts every queued save at once. Each blocking transfer holds a shared IO thread,
     * so a large playlist would starve DataStore and the UI; the rest wait here without a thread.
     */
    internal suspend fun <T> limitedTransfer(block: suspend () -> T): T = transfers.withPermit { block() }
}

class OfflineAudioWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("videoId") ?: return Result.failure()
        val account = inputData.getString("accountKey")
        val playlist = inputData.getString("playlistId")
        val modeStore = ModeStore(applicationContext)
        val config = AutoLibraryConfig(applicationContext)
        val automatic = account != null && playlist != null
        val authorized: suspend () -> Boolean = {
            modeStore.mode.first() == HomeDjMode.SERVER &&
                (!automatic || config.isDownloadCurrent(account.orEmpty(), playlist.orEmpty()))
        }
        if (!NativeAudioSource.validId(id)) return Result.success()
        if (!authorized()) {
            OfflineAudioLibrary.discardPartials(applicationContext, id, automatic)
            return Result.success()
        }
        return try {
            val file = OfflineAudioLibrary.download(applicationContext, id, automatic, authorized)
            Log.i("HomeDJOffline", "Download ready: videoId=" + id + " bytes=" + file.length())
            val track = inputData.getString("title")?.takeIf { it.isNotBlank() }
                ?.let { YouTubeTrack(id, it, inputData.getString("artist").orEmpty(), inputData.getInt("duration", 0)) }
                ?: SavedPlaylistStore.shared(applicationContext).read().songs()[id]
                ?: YouTubeTrack(id, id)
            withContext(Dispatchers.IO) {
                OfflineSongInfo.save(applicationContext, track)
                OfflineSongInfo.saveArt(applicationContext, id)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            // Cancelled by the user (cancel or remove download): the partial would never be resumed.
            if (stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                OfflineAudioLibrary.discardPartials(applicationContext, id, automatic)
            }
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJOffline", "Audio download failed: videoId=" + id +
                " error=" + failure.javaClass.simpleName)
            // NewPipe's downloader retries 3 times; a resumed attempt continues the kept partial file.
            if (failure !is TrackUnavailableException && runAttemptCount < 3 && authorized()) {
                Result.retry()
            } else {
                OfflineAudioLibrary.discardPartials(applicationContext, id, automatic)
                Result.failure()
            }
        }
    }
}
