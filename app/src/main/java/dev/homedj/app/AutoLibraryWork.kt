package dev.homedj.app

import android.content.Context
import android.content.pm.PackageManager
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

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        schedule()
    }

    fun togglePlaylist(id: String) {
        if (!TermuxPlaylistDownloads.validId(id)) return
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
 * Download handoff is best-effort, not evidence that Termux downloaded anything successfully.
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
            if (!config.enabled || ModeStore(app).mode.first() != HomeDjMode.SERVER) return Result.success()
            val saved = SavedPlaylistStore.shared(app).replaceOwned(account, playlists)
            Log.i("HomeDJAuto", "Playlist metadata refreshed: count=" + saved.playlists.size)
            val selected = config.playlistIds.intersect(saved.playlists.map { it.id }.toSet()).take(20)
            if (selected.isNotEmpty() && config.enabled && 
                app.checkSelfPermission(TermuxPlaylistDownloads.PERMISSION) == PackageManager.PERMISSION_GRANTED &&
                TermuxPlaylistDownloads.isInstalled(app)) {
                val accepted = TermuxPlaylistDownloads.startBatch(app, selected)
                Log.i("HomeDJAuto", "Termux download handoff: accepted=" + accepted + " playlists=" + selected.size)
                if (!accepted) return Result.retry()
            } else if (selected.isNotEmpty()) {
                Log.w("HomeDJAuto", "Automatic downloads blocked: Termux missing or permission not granted")
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJAuto", "Background playlist sync failed: " + failure.javaClass.simpleName)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
