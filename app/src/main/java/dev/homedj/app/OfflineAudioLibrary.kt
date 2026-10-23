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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
    private val extensions = listOf("m4a", "webm", "opus")
    private val commitMutex = Mutex()
    internal const val MAX_PARALLEL_TRANSFERS = 4
    private val transfers = Semaphore(MAX_PARALLEL_TRANSFERS)
    private const val SAVE_TAG = "homedj_offline"
    private const val ID_TAG_PREFIX = "homedj_offline_id_"
    private val changes = MutableStateFlow(System.currentTimeMillis())
    /**
     * Bumped when a save is queued or stopped, and after a song is saved or removed, so download
     * marks on both phones re-read files and pending saves. Starting from wall-clock time keeps it
     * from repeating an older value after a process restart.
     */
    val revision: StateFlow<Long> = changes.asStateFlow()

    internal fun changed() = changes.update { it + 1 }

    fun directory(context: Context): File = File(context.applicationContext.filesDir, "offline_audio")
    fun existing(context: Context, videoId: String): File? = existing(directory(context), videoId)

    private fun existing(folder: File, videoId: String): File? {
        if (!NativeAudioSource.validId(videoId)) return null
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
            savedFiles(directory(context))
                .sortedByDescending { it.lastModified() }
                .map { file ->
                    val id = file.nameWithoutExtension
                    offlineTrack(id, OfflineSongInfo.read(context, id), catalog)
                }
        }
    }

    /** A single directory pass for LAN download marks; no metadata reads or per-song extension probes. */
    suspend fun savedIds(context: Context): Set<String> = withContext(Dispatchers.IO) {
        savedFiles(directory(context)).mapTo(hashSetOf()) { it.nameWithoutExtension }
    }

    internal fun savedFiles(folder: File): List<File> = folder.listFiles().orEmpty()
        .filter { it.isFile && it.length() > 0 && extensions.contains(it.extension) &&
            NativeAudioSource.validId(it.nameWithoutExtension) }

    /** Saved details win; a song no saved playlist lists any more keeps its video ID as its name. */
    internal fun offlineTrack(videoId: String, saved: YouTubeTrack?, catalog: Map<String, YouTubeTrack>): YouTubeTrack =
        saved ?: catalog[videoId] ?: YouTubeTrack(videoId, videoId)

    /**
     * Stops queued or running saves of these songs, manual and automatic. A running worker drops its
     * partial file; automatic saves come back on the next sync while their playlist stays opted in.
     */
    suspend fun cancel(context: Context, ids: Collection<String>) {
        // First, so a top-up or backup pass running meanwhile cannot queue these songs again.
        PlaylistDownloads.forget(context, ids)
        DownloadBackup.forget(context, ids)
        cancelWork(context, ids)
        DownloadNotice.update(context, ids)
    }

    private fun cancelWork(context: Context, ids: Collection<String>) {
        val work = WorkManager.getInstance(context.applicationContext)
        ids.filter(NativeAudioSource::validId).forEach { id ->
            work.cancelUniqueWork(workName(id, automatic = false))
            work.cancelUniqueWork(workName(id, automatic = true))
        }
        changed()
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
        PlaylistDownloads.forget(context, valid)
        DownloadBackup.forget(context, valid)
        cancelWork(context, valid)
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
        }.also {
            changed()
            DownloadNotice.update(context, valid)
        }
    }

    /**
     * Saves started by a sync name their playlist and wait for unmetered Wi-Fi: auto-save
     * (with [accountKey]) and songs newly added to a downloaded playlist (without). User taps name none.
     * The hourly sync's [backup] try of a failed tap waits for Wi-Fi too.
     */
    internal fun networkForDownload(accountKey: String?, playlistId: String?, backup: Boolean = false): NetworkType =
        if (playlistId != null || backup) NetworkType.UNMETERED else NetworkType.CONNECTED

    /**
     * Manual and automatic saves use separate unique work and partial files, so a pending
     * Wi-Fi-only auto job whose opt-in was withdrawn never swallows an explicit manual save.
     */
    internal fun workName(id: String, automatic: Boolean): String =
        (if (automatic) "homedj_auto_audio_" else "homedj_audio_") + id

    /** Keyed by stream itag: a resumed attempt only appends to bytes of the same stream format. */
    internal fun partialFile(folder: File, id: String, automatic: Boolean, itag: Int): File =
        File(folder, if (automatic) "$id.$itag.auto.part" else "$id.$itag.part")

    internal fun isPartialOf(name: String, id: String, automatic: Boolean): Boolean =
        Regex(Regex.escape(id) + (if (automatic) """\.\d+\.auto\.part""" else """\.\d+\.part"""))
            .matches(name)

    fun discardPartials(context: Context, id: String, automatic: Boolean) {
        directory(context).listFiles().orEmpty()
            .filter { isPartialOf(it.name, id, automatic) }
            .forEach { it.delete() }
    }

    /** Returns false without queuing work when the song is already downloaded. Reads files: call off Main. */
    fun enqueue(
        context: Context, track: YouTubeTrack, accountKey: String? = null,
        playlistId: String? = null, backup: Boolean = false,
    ): Boolean {
        require(NativeAudioSource.validId(track.id)) { "Invalid video ID" }
        if (existing(context, track.id) != null) return false
        // Song details are optional input: work queued by older builds lacks them and uses the catalog.
        val params = Data.Builder()
            .putString("videoId", track.id)
            .putString("accountKey", accountKey)
            .putString("playlistId", playlistId)
            .putString("title", track.title.take(300))
            .putString("artist", track.artist.take(200))
            .putInt("duration", track.durationSeconds)
            .putBoolean(DownloadBackup.INPUT_BACKUP, backup)
            .build()
        val automatic = accountKey != null && playlistId != null
        val request = OneTimeWorkRequestBuilder<OfflineAudioWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(
                networkForDownload(accountKey, playlistId, backup)
            ).build())
            .setInputData(params)
            .addTag(SAVE_TAG)
            .addTag(ID_TAG_PREFIX + track.id)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName(track.id, automatic), ExistingWorkPolicy.KEEP, request,
        )
        changed()
        return true
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

    /**
     * Video IDs whose last save failed, while WorkManager keeps the
     * finished work (at least a day). A song saved meanwhile under the other work name is not failed.
     */
    fun failedIds(context: Context): Flow<Set<String>> =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosFlow(WorkQuery.Builder.fromTags(listOf(SAVE_TAG))
                .addStates(listOf(WorkInfo.State.FAILED)).build())
            .map { infos -> idsFromTags(infos.map(WorkInfo::tags)) }
            .distinctUntilChanged()

    /** Skip errors in this run; an explicit save can try the missing song again. */
    internal const val MAX_RETRIES = 0

    /** The zero retry budget applies to work already queued by older builds too. */
    internal fun retryable(failure: Exception, runAttemptCount: Int): Boolean =
        runAttemptCount < MAX_RETRIES && (failure !is TrackUnavailableException || failure is BotCheckException)

    /** Only controlled failure codes reach logs; exception text can contain signed URLs or bodies. */
    internal fun failureDetail(failure: Exception): String {
        val message = failure.message.orEmpty()
        val extractor = Regex("(?:Extraction failed: |Search failed: )?([A-Za-z][A-Za-z0-9]{0,70}Exception)")
            .matchEntire(message)?.groupValues?.get(1)
        if (extractor != null) return extractor
        Regex("Audio HTTP status ([1-5][0-9]{2})").matchEntire(message)?.let {
            return "http_" + it.groupValues[1]
        }
        return when (message) {
            "No unprotected, directly downloadable audio stream available" -> "no_audio_stream"
            "Missing Content-Range" -> "missing_content_range"
            "Audio chunk incomplete" -> "incomplete_chunk"
            "Device storage nearly full" -> "device_storage"
            else -> "unspecified"
        }
    }

    internal fun idsFromTags(tagSets: List<Set<String>>): Set<String> =
        tagSets.flatMapTo(hashSetOf()) { tags ->
            tags.mapNotNull { tag -> tag.removePrefix(ID_TAG_PREFIX).takeIf { tag.startsWith(ID_TAG_PREFIX) } }
        }

    /** [track] names the song for [SongSubstitute] when YouTube refuses its own video. */
    internal suspend fun download(
        context: Context, track: YouTubeTrack, automatic: Boolean, authorized: suspend () -> Boolean,
    ): File {
        val id = track.id
        require(NativeAudioSource.validId(id))
        // A saved song never waits behind other songs' transfers for a permit.
        withContext(Dispatchers.IO) { existing(context, id) }?.let { return it }
        val resolve = SongSubstitute.resolver(track, onSubstitute = { substitute ->
            Log.i("HomeDJOffline", "Download substitute: videoId=" + id + " substitute=" + substitute)
        })
        return limitedTransfer {
            transfer(directory(context), id, automatic, authorized, resolve, onFallback = { step, failure ->
                Log.i("HomeDJOffline", "Download fallback: videoId=" + id + " step=" + step +
                    " detail=" + failureDetail(failure))
            })
        }
    }

    /**
     * Saves the best offered stream, falling back per [DownloadBackup.step] on transfer errors.
     * A rejection, cancellation or extraction failure ends the attempt as before.
     */
    internal suspend fun transfer(
        folder: File, id: String, automatic: Boolean, authorized: suspend () -> Boolean,
        resolve: suspend (String) -> List<NativeAudioSource.Link> = NativeAudioSource::resolveAll,
        fetch: suspend (String, File, suspend (Long) -> Unit) -> Long = AudioTransfer::fetch,
        onFallback: (DownloadBackup.Step, IOException) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        // The manual or automatic save of the same song may have committed while this waited.
        existing(folder, id)?.let { return@withContext it }
        if (!authorized()) throw DownloadRejected("Download no longer authorized")
        val noStream = "No unprotected, directly downloadable audio stream available"
        val links = resolve(id).ifEmpty { throw TrackUnavailableException(noStream) }
        if (!folder.exists() && !folder.mkdirs()) throw IOException("Cannot create audio directory")
        withFallback(folder, id, automatic, links, authorized, resolve, fetch, onFallback)
    }

    private suspend fun withFallback(
        folder: File, id: String, automatic: Boolean, offered: List<NativeAudioSource.Link>,
        authorized: suspend () -> Boolean, resolve: suspend (String) -> List<NativeAudioSource.Link>,
        fetch: suspend (String, File, suspend (Long) -> Unit) -> Long,
        onFallback: (DownloadBackup.Step, IOException) -> Unit,
    ): File {
        var links = offered
        var index = 0
        var resumes = 0
        var refreshed = false
        while (true) {
            val link = links[index]
            val before = partialFile(folder, id, automatic, link.itag).length()
            try {
                return save(folder, id, automatic, link, authorized, fetch)
            } catch (rejected: DownloadRejected) {
                throw rejected
            } catch (failure: IOException) {
                // New bytes mean the link still works: the resume budget counts failures in a row.
                if (partialFile(folder, id, automatic, link.itag).length() > before) resumes = 0
                val step = DownloadBackup.step(failure, resumes, refreshed, index + 1 < links.size)
                onFallback(step, failure)
                when (step) {
                    DownloadBackup.Step.RESUME -> resumes++
                    DownloadBackup.Step.REFRESH -> {
                        refreshed = true
                        if (!authorized()) throw DownloadRejected("Download no longer authorized")
                        links = resolve(id).ifEmpty { throw failure }
                        index = 0
                        resumes = 0
                    }
                    DownloadBackup.Step.NEXT_STREAM -> {
                        index++
                        resumes = 0
                    }
                    DownloadBackup.Step.GIVE_UP -> throw failure
                }
            }
        }
    }

    private suspend fun save(
        folder: File, id: String, automatic: Boolean, link: NativeAudioSource.Link,
        authorized: suspend () -> Boolean, fetch: suspend (String, File, suspend (Long) -> Unit) -> Long,
    ): File {
        val destination = File(folder, "$id.${link.extension}")
        val partial = partialFile(folder, id, automatic, link.itag)
        // A partial for another stream format cannot be resumed with this link.
        folder.listFiles().orEmpty()
            .filter { isPartialOf(it.name, id, automatic) && it != partial }
            .forEach { it.delete() }
        return try {
            fetch(link.url, partial) {
                if (!authorized()) throw DownloadRejected("Download stopped: server mode or consent changed")
                if (folder.usableSpace < 10L * 1024 * 1024) throw DownloadRejected("Device storage nearly full")
            }
            coroutineContext.ensureActive()
            commitMutex.withLock {
                // A removal may have cancelled this save while it waited for the lock.
                coroutineContext.ensureActive()
                if (!authorized()) throw DownloadRejected("Download no longer authorized")
                if (!partial.renameTo(destination)) throw DownloadRejected("Unable to commit downloaded audio")
            }
            destination
        } catch (rejected: DownloadRejected) {
            // Any other failure keeps the partial so the next worker attempt resumes it.
            partial.delete()
            throw rejected
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
            DownloadNotice.update(applicationContext, listOf(id))
            return Result.success()
        }
        DownloadNotice.started(applicationContext, id)
        val result = try {
            val track = inputData.getString("title")?.takeIf { it.isNotBlank() }
                ?.let { YouTubeTrack(id, it, inputData.getString("artist").orEmpty(), inputData.getInt("duration", 0)) }
                ?: SavedPlaylistStore.shared(applicationContext).read().songs()[id]
                ?: YouTubeTrack(id, id)
            val file = OfflineAudioLibrary.download(applicationContext, track, automatic, authorized)
            Log.i("HomeDJOffline", "Download ready: videoId=" + id + " bytes=" + file.length())
            withContext(Dispatchers.IO) {
                OfflineSongInfo.save(applicationContext, track)
                // Audio and details are ready; artwork must not delay live downloaded marks.
                OfflineAudioLibrary.changed()
                OfflineSongInfo.saveArt(applicationContext, id)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            // Cancelled by the user (cancel or remove download): the partial would never be resumed.
            if (stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                OfflineAudioLibrary.discardPartials(applicationContext, id, automatic)
            }
            withContext(NonCancellable) { DownloadNotice.update(applicationContext, listOf(id)) }
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJOffline", "Audio download failed: videoId=" + id +
                " error=" + failure.javaClass.simpleName + " detail=" + OfflineAudioLibrary.failureDetail(failure))
            // A resumed attempt continues the kept partial file.
            if (OfflineAudioLibrary.retryable(failure, runAttemptCount) && authorized()) {
                Result.retry()
            } else {
                OfflineAudioLibrary.discardPartials(applicationContext, id, automatic)
                if (DownloadBackup.wantsLater(failure, automatic, inputData.getBoolean(DownloadBackup.INPUT_BACKUP, false))) {
                    DownloadBackup.remember(applicationContext, id)
                }
                Result.failure()
            }
        }
        // A retry stays queued; a saved or failed song leaves the notification's count and
        // makes room for the next batch of a whole-playlist download.
        if (result !is Result.Retry) {
            DownloadNotice.update(applicationContext, listOf(id))
            PlaylistDownloads.continueLater(applicationContext)
        }
        return result
    }
}
