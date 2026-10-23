package dev.homedj.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Restore the user's existing opt-in after unlock at boot or an in-place app update. */
class RemoteServerRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val app = context.applicationContext
        val pending = goAsync()
        // This finite receiver-owned job must finish before the broadcast is released.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    if (remoteServerAllowed(ModeStore(app).mode.first(), RemotePrefs(app).enabled)) {
                        app.startForegroundService(Intent(app, RemoteServerService::class.java))
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                Log.w("HomeDJRemote", "Remote restore timed out")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("HomeDJRemote", "Remote restore failed: " + failure.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }
}

internal fun remoteServerAllowed(mode: HomeDjMode?, enabled: Boolean): Boolean =
    mode == HomeDjMode.SERVER && enabled
