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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

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

    /**
     * Hourly playlist refresh in Server mode, whether or not auto-save is on: link playlists and
     * downloaded playlists follow YouTube too. UPDATE moves a job queued by an older build (every
     * 12 hours, only while auto-save was on) to this period while keeping its schedule.
     */
    fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        val periodic = PeriodicWorkRequestBuilder<AutoLibraryWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(app)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodic)
    }

    private companion object { const val WORK_NAME = "homedj_playlist_sync" }
}

/** A silently granted YouTube token (memory only) and the account it belongs to. */
internal class YouTubeAccess(val token: String, val account: String)

/**
 * Google authorization without UI, for workers and LAN commands; null when it would need the
 * person (never launched from here), is unavailable, or names no account.
 */
internal suspend fun silentYouTubeAccess(context: Context): YouTubeAccess? = try {
    val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/youtube.readonly")))
        .build()
    val task = Identity.getAuthorizationClient(context.applicationContext).authorize(request)
    val response = suspendCancellableCoroutine<com.google.android.gms.auth.api.identity.AuthorizationResult> { continuation ->
        task.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        task.addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    }
    val token = response.accessToken
    val account = response.toGoogleSignInAccount()?.account?.name
    if (response.hasResolution() || token.isNullOrBlank() || account.isNullOrBlank()) null
    else YouTubeAccess(token, account)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Log.i("HomeDJAuto", "Silent YouTube authorization unavailable: " + failure.javaClass.simpleName)
    null
}

/**
 * Hourly in Server mode: refreshes the connected account's playlist list, then every saved
 * playlist's songs through [PlaylistRefresh] (songs new to a downloaded playlist queue for Wi-Fi),
 * then, with auto-save on, queues whole auto-save playlists as before. Authorization is silent only.
 */
class AutoLibraryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val config = AutoLibraryConfig(app)
        val modes = ModeStore(app)
        if (modes.mode.first() != HomeDjMode.SERVER) return Result.success()
        return try {
            val store = SavedPlaylistStore.shared(app)
            // The token reads only its own account's snapshot; link playlists need none.
            val access = silentYouTubeAccess(app)?.takeIf {
                store.read().ownerKey == SavedPlaylistStore.accountKey(it.account)
            }
            if (access != null) try {
                val playlists = YouTubePlaylistRepository().playlists(access.token)
                if (modes.mode.first() != HomeDjMode.SERVER) return Result.success()
                store.replaceOwned(access.account, playlists) {
                    it.ownerKey == SavedPlaylistStore.accountKey(access.account)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeDJAuto", "Account playlist list refresh failed: " + failure.javaClass.simpleName)
            }
            val autoSave = access != null && config.isCurrent(access.account)
            var budget = PlaylistRefresh.MAX_QUEUED_PER_PASS
            val playlists = store.read().playlists.take(PlaylistRefresh.MAX_PLAYLISTS_PER_PASS)
            for (playlist in playlists) {
                if (modes.mode.first() != HomeDjMode.SERVER) return Result.success()
                // One deleted/private playlist must not block the others every pass.
                try {
                    // Auto-save playlists are queued whole below, as account saves.
                    val wholeLater = autoSave && playlist.id in config.playlistIds
                    val outcome = PlaylistRefresh.refresh(app, access?.token, playlist.id,
                        budget = if (wholeLater) 0 else budget)
                    if (outcome is RefreshOutcome.Done) budget -= outcome.queued
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("HomeDJAuto", "Playlist refresh skipped: " + failure.javaClass.simpleName)
                }
            }
            if (access != null && autoSave) budget -= queueAutoSave(app, config, access.account, budget)
            // One later Wi-Fi try for taps that failed in a way time may fix; never blocks the top-up.
            try {
                budget -= DownloadBackup.requeue(app, budget)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeDJAuto", "Download backup pass skipped: " + failure.javaClass.simpleName)
            }
            // Safety net for whole-playlist downloads whose last top-up was skipped.
            PlaylistDownloads.topUp(app)
            Log.i("HomeDJAuto", "Playlists refreshed: count=" + playlists.size +
                " queued=" + (PlaylistRefresh.MAX_QUEUED_PER_PASS - budget))
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJAuto", "Background playlist sync failed: " + failure.javaClass.simpleName)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    /** Whole auto-save playlists from the refreshed snapshot; returns how many saves were queued. */
    private suspend fun queueAutoSave(app: Context, config: AutoLibraryConfig, account: String, budget: Int): Int {
        val saved = SavedPlaylistStore.shared(app).read()
        val pending = OfflineAudioLibrary.pendingIds(app).first()
        val owner = SavedPlaylistStore.accountKey(account)
        var queued = 0
        for (playlistId in config.playlistIds.intersect(saved.playlists.map { it.id }.toSet()).take(10)) {
            for (track in saved.tracks[playlistId].orEmpty()) {
                if (queued >= budget || !config.isCurrent(account) || playlistId !in config.playlistIds) return queued
                // A save already pending (a tap or a refresh) is not started twice under another name.
                if (!NativeAudioSource.validId(track.id) || track.id in pending) continue
                if (withContext(Dispatchers.IO) { OfflineAudioLibrary.enqueue(app, track, owner, playlistId) }) queued++
            }
        }
        return queued
    }
}
