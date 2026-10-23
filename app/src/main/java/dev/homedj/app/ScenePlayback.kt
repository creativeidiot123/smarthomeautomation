package dev.homedj.app

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hands a light scene's linked playlists to PlaybackService's sole player (MusicScheduler decides
 * and plays); this only delivers the request, like the schedule alarm does, and reports the reply.
 */
internal object ScenePlayback {
    private const val REPLY_MILLIS = 5_000L

    /**
     * Text added to the scene's outcome; a timeout is an unknown outcome, not a failure.
     * [ifIdle]: leave music that is already playing alone (coming home).
     */
    suspend fun start(context: Context, sceneId: String, playlists: List<String>, ifIdle: Boolean = false): String {
        val app = context.applicationContext
        val args = Bundle().apply {
            putString(MusicScheduler.ARG_SCENE, sceneId)
            putStringArrayList(MusicScheduler.ARG_PLAYLISTS, ArrayList(playlists))
            putBoolean(MusicScheduler.ARG_IF_IDLE, ifIdle)
        }
        return try {
            val result = withContext(Dispatchers.Main) {
                withTimeoutOrNull(REPLY_MILLIS) {
                    val future = MediaController.Builder(
                        app, SessionToken(app, ComponentName(app, PlaybackService::class.java)),
                    ).buildAsync()
                    try {
                        future.await(app).sendCustomCommand(MusicScheduler.PLAY_SCENE, args).await(app)
                    } finally {
                        MediaController.releaseFuture(future)
                    }
                }
            }
            when {
                result == null -> "music did not answer"
                result.resultCode == SessionResult.RESULT_SUCCESS ->
                    "playing ${result.extras.getInt(MusicScheduler.ARG_SONGS)} songs shuffled"
                else -> "music not started: " + (result.sessionError?.message ?: "music service did not respond")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJ", "Scene music failed: " + failure.javaClass.simpleName)
            "music not started: music service did not respond"
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(context: Context): T = suspendCancellableCoroutine { continuation ->
        addListener({
            try {
                continuation.resume(get())
            } catch (failure: ExecutionException) {
                continuation.resumeWithException(failure.cause ?: failure)
            } catch (cancelled: CancellationException) {
                // Only our own cancel below; never throw on the main thread.
                continuation.cancel(cancelled)
            }
        }, context.mainExecutor)
        continuation.invokeOnCancellation { cancel(false) }
    }
}
