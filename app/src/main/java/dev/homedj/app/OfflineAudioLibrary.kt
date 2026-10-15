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
import java.net.HttpURLConnection
import java.net.URL
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

    internal fun partialFile(context: Context, id: String, automatic: Boolean): File =
        File(directory(context), if (automatic) "$id.auto.part" else "$id.part")

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
            if (!authorized()) throw IOException("Download no longer authorized")
            val link = NativeAudioSource.resolve(id)
            val folder = directory(context)
            if (!folder.exists() && !folder.mkdirs()) throw IOException("Cannot create audio directory")
            val destination = File(folder, "$id.${link.extension}")
            val partial = partialFile(context, id, automatic)
            val existingSize = folder.listFiles().orEmpty().filter { it.isFile && it.extension != "part" }
                .sumOf { it.length().coerceAtLeast(0) }
            if (existingSize >= MAX_LIBRARY_BYTES) throw IOException("Offline library storage limit reached")
            val connection = URL(link.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "audio/*")
            connection.instanceFollowRedirects = false
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("Audio HTTP status " + connection.responseCode)
                }
                val contentType = connection.contentType?.lowercase(java.util.Locale.ROOT).orEmpty()
                if (!contentType.startsWith("audio/") && !contentType.startsWith("video/")) {
                    throw IOException("Media server returned a non-audio response")
                }
                val declared = connection.contentLengthLong
                if (declared > MAX_TRACK_BYTES || declared + existingSize > MAX_LIBRARY_BYTES && declared > 0) {
                    throw IOException("Insufficient offline storage budget")
                }
                partial.outputStream().buffered().use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var countTotal = 0L
                        var bytesSinceRoleCheck = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            countTotal += size
                            bytesSinceRoleCheck += size
                            if (bytesSinceRoleCheck >= 1024 * 1024) {
                                if (!authorized()) throw IOException("Download stopped: server mode or consent changed")
                                bytesSinceRoleCheck = 0
                            }
                            if (countTotal > MAX_TRACK_BYTES || countTotal + existingSize > MAX_LIBRARY_BYTES) {
                                throw IOException("Audio exceeds offline storage budget")
                            }
                            if (folder.usableSpace < 10L * 1024 * 1024) throw IOException("Device storage nearly full")
                            output.write(buffer, 0, size)
                        }
                        if (countTotal == 0L || declared > 0 && countTotal != declared) {
                            throw IOException("Audio transfer incomplete")
                        }
                    }
                }
                coroutineContext.ensureActive()
                if (!authorized()) throw IOException("Download no longer authorized")
                commitMutex.withLock {
                    if (!authorized()) throw IOException("Download no longer authorized")
                    val committedBytes = folder.listFiles().orEmpty()
                        .filter { it.isFile && it.extension in extensions }
                        .sumOf { it.length().coerceAtLeast(0) }
                    if (committedBytes + partial.length() > MAX_LIBRARY_BYTES) {
                        throw IOException("Offline library size limit reached")
                    }
                    if (!partial.renameTo(destination)) throw IOException("Unable to commit downloaded audio")
                }
                destination
            } finally {
                connection.disconnect()
                if (partial.exists()) partial.delete()
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
        // A killed earlier attempt of this unique job may have left a partial file behind.
        OfflineAudioLibrary.partialFile(applicationContext, id, automatic).delete()
        if (!authorized()) return Result.success()
        return try {
            val file = OfflineAudioLibrary.download(applicationContext, id, automatic, authorized)
            Log.i("HomeDJOffline", "Download ready: videoId=" + id + " bytes=" + file.length())
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJOffline", "Audio download failed: videoId=" + id +
                " error=" + failure.javaClass.simpleName)
            if (runAttemptCount < 2 && authorized()) Result.retry() else Result.failure()
        }
    }
}
