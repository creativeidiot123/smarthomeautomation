package dev.homedj.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.time.ZonedDateTime
import java.util.concurrent.ExecutionException

/**
 * One exact alarm for the next schedule boundary. Exact alarms are what let Android start the
 * media-playback foreground service from the background at a slot's start.
 */
internal object ScheduleAlarms {
    private const val ACTION_TICK = "dev.homedj.app.action.SCHEDULE_TICK"
    private const val SETTLE_SECONDS = 3L

    fun canSchedule(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun arm(context: Context, at: ZonedDateTime?) {
        if (at == null) return cancel(context)
        if (!canSchedule(context)) {
            Log.w("HomeDJ", "Exact alarms not allowed; schedule cannot start on time")
            return
        }
        context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), tickIntent(context),
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(tickIntent(context))
    }

    /** Reconciles now from a visible screen, e.g. after the schedule was edited. */
    fun tickNow(context: Context) {
        context.sendBroadcast(Intent(context, ScheduleAlarmReceiver::class.java).setAction(ACTION_TICK))
    }

    /**
     * Boot and clock changes only arm a near alarm: Android 15 forbids starting media playback
     * straight from BOOT_COMPLETED, while an exact alarm may.
     */
    fun armSoon(context: Context) = arm(context, ZonedDateTime.now().plusSeconds(SETTLE_SECONDS))

    fun isTick(intent: Intent): Boolean = intent.action == ACTION_TICK

    private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ScheduleAlarmReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Not exported: only the system and this app can deliver boot, clock and alarm broadcasts. */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (!ScheduleAlarms.isTick(intent)) {
            ScheduleAlarms.armSoon(app)
            return
        }
        // PlaybackService owns the player and the decision; this only delivers the boundary.
        val pending = goAsync()
        val future = MediaController.Builder(
            app, SessionToken(app, ComponentName(app, PlaybackService::class.java)),
        ).buildAsync()
        future.addListener({
            val controller = try {
                future.get()
            } catch (failure: ExecutionException) {
                Log.w("HomeDJ", "Schedule tick could not reach playback: " + failure.javaClass.simpleName)
                pending.finish()
                return@addListener
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                pending.finish()
                return@addListener
            }
            controller.sendCustomCommand(MusicScheduler.TICK, Bundle.EMPTY).addListener({
                MediaController.releaseFuture(future)
                pending.finish()
            }, app.mainExecutor)
        }, app.mainExecutor)
    }
}
