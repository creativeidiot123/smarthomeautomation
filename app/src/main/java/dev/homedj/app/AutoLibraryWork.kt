package dev.homedj.app

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine

/** Opt-in device-local download policy. Only Server mode is allowed to perform work. */
internal class AutoLibraryConfig(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("homedj_auto_library", Context.MODE_PRIVATE)
    val enabled: Boolean get() = prefs.getBoolean("enabled", false)
    val playlistIds: Set<String> get() = prefs.getStringSet("playlists", emptySet()).orEmpty().toSet()
    private val accountKey: String? get() = prefs.getString("accountKey", null)

    /** Auto-download selection belongs to exactly one Google account. */
    fun bindAccount(accountName: String) {
        val next = SavedPlaylistStore.accountKey(accountName)
        if (accountKey != next) {
            prefs.edit().putString("accountKey", next).remove("playlists")
                .putBoolean("enabled", false).commit()
            schedule()
        }
    }

    fun isCurrent(accountName: String): Boolean =
        enabled && accountKey == SavedPlaylistStore.accountKey(accountName)

    fun isDownloadCurrent(owner: String, playlistId: String): Boolean =
        enabled && accountKey == owner && playlistId in playlistIds

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        schedule()
    }

    fun togglePlaylist(id: String) {
        if (id.length !in 2..150 || id.any {
                !it.isLetterOrDigit() && it != '_' && it != '-'
            }) return
        val next = playlistIds.toMutableSet()
        if (!next.add(id)) next.remove(id)
        prefs.edit().putStringSet("playlists", next).apply()
    }

    fun erase() {
        prefs.edit().clear().commit()
        schedule()
    }

    fun schedule() {
        val manager = WorkManager.getInstance(app)
        if (!enabled) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        val periodic = PeriodicWorkRequestBuilder<AutoLibraryWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    private companion object { const val WORK_NAME = "homedj_playlist_sync" }
}

/**
 * Google authorization is silent only; an interactive resolution is never launched by a Worker.
 * Selected playlists enqueue native WorkManager audio downloads with stable media IDs.
 */
class AutoLibraryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val config = AutoLibraryConfig(app)
        if (!config.enabled || ModeStore(app).mode.first() != HomeDjMode.SERVER) return Result.success()
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/youtube.readonly")))
                .build()
            val task = Identity.getAuthorizationClient(app).authorize(request)
            val response = suspendCancellableCoroutine<com.google.android.gms.auth.api.identity.AuthorizationResult> { continuation ->
                task.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                task.addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            }
            val token = response.accessToken
            if (response.hasResolution() || token.isNullOrBlank()) {
                Log.i("HomeDJAuto", "Silent account authorization unavailable; connect in Server mode")
                return Result.success()
            }
            val account = response.toGoogleSignInAccount()?.account?.name
            if (account.isNullOrBlank()) {
                Log.w("HomeDJAuto", "Account identity unavailable; skipping background sync")
                return Result.success()
            }
            val playlists = YouTubePlaylistRepository().playlists(token)
            if (!config.isCurrent(account) || ModeStore(app).mode.first() != HomeDjMode.SERVER) {
                return Result.success()
            }
            val saved = SavedPlaylistStore.shared(app).replaceOwned(account, playlists) {
                config.isCurrent(account) && it.ownerKey == SavedPlaylistStore.accountKey(account)
            }
            if (!config.isCurrent(account)) return Result.success()
            Log.i("HomeDJAuto", "Playlist metadata refreshed: count=" + saved.playlists.size)
            val selected = config.playlistIds.intersect(saved.playlists.map { it.id }.toSet()).take(10)
            val queued = hashSetOf<String>()
            for (playlistId in selected) {
                if (!config.isCurrent(account) || playlistId !in config.playlistIds) break
                // One deleted/private/oversized playlist must not block the others every pass.
                val tracks = try {
                    YouTubePlaylistRepository().tracks(token, playlistId).also {
                        SavedPlaylistStore.shared(app).storeTracks(account, playlistId, it)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("HomeDJAuto", "Playlist sync skipped: " + failure.javaClass.simpleName)
                    continue
                }
                for (track in tracks) {
                    if (queued.size >= 150 || !config.isCurrent(account) ||
                        playlistId !in config.playlistIds) break
                    if (NativeAudioSource.validId(track.id) && queued.add(track.id)) {
                        OfflineAudioLibrary.enqueue(app, track,
                            SavedPlaylistStore.accountKey(account), playlistId)
                    }
                }
                if (queued.size >= 150) break
            }
            Log.i("HomeDJAuto", "Native offline downloads queued: " + queued.size)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJAuto", "Background playlist sync failed: " + failure.javaClass.simpleName)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
