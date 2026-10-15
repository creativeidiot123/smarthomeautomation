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
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    fun allLocalMedia(context: Context): List<LocalAudio> =
        directory(context).listFiles().orEmpty()
            .filter { it.isFile && it.length() > 0 && extensions.contains(it.extension) &&
                NativeAudioSource.validId(it.nameWithoutExtension) }
            .sortedBy { it.name }
            .map { LocalAudio(Uri.fromFile(it), it.nameWithoutExtension) }

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
        val params = Data.Builder()
            .putString("videoId", track.id)
            .putString("accountKey", accountKey)
            .putString("playlistId", playlistId)
            .build()
        val automatic = accountKey != null && playlistId != null
        val request = OneTimeWorkRequestBuilder<OfflineAudioWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(
                networkForDownload(accountKey, playlistId)
            ).build())
            .setInputData(params)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName(track.id, automatic), ExistingWorkPolicy.KEEP, request,
        )
    }

    internal suspend fun download(
        context: Context, id: String, automatic: Boolean, authorized: suspend () -> Boolean,
    ): File =
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
            Result.success()
        } catch (cancelled: CancellationException) {
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
