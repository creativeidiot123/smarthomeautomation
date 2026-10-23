package dev.homedj.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException

/**
 * Samsung's Lights automation triggers. A rule alarm, boot or clock change runs the rules that
 * came due; the OnePlus leaving or returning runs presence. Both hand the Matter work to
 * [LightsWorker], so it outlives the broadcast and the LAN listener's tick. Work names and the
 * `trigger` input are persisted contracts.
 */
internal object LightsWork {
    private const val ACTION_RULES = "dev.homedj.app.action.LIGHTS_RULES"
    private const val RULES_WORK = "homedj_lights_rules"
    private const val PRESENCE_WORK = "homedj_lights_presence"
    const val TRIGGER = "trigger"
    const val RULES = "rules"
    const val HOME = "home"
    const val AWAY = "away"

    /** Exact when Samsung allows it; otherwise Android may run it a few minutes late. */
    fun arm(context: Context, at: ZonedDateTime?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = rulesIntent(context)
        if (at == null) return alarms.cancel(intent)
        val millis = at.toInstant().toEpochMilli()
        if (ScheduleAlarms.canSchedule(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        }
    }

    /** Queued after a run in progress, so a rule that comes due meanwhile is not lost. */
    fun runRules(context: Context) = enqueue(context, RULES_WORK, RULES, ExistingWorkPolicy.APPEND_OR_REPLACE)

    /** Latest wins: coming home cancels a leave still turning lights off. */
    fun presence(context: Context, home: Boolean) =
        enqueue(context, PRESENCE_WORK, if (home) HOME else AWAY, ExistingWorkPolicy.REPLACE)

    private fun enqueue(context: Context, name: String, trigger: String, policy: ExistingWorkPolicy) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, policy,
            OneTimeWorkRequestBuilder<LightsWorker>().setInputData(workDataOf(TRIGGER to trigger)).build())
    }

    private fun rulesIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, LightsAlarmReceiver::class.java).setAction(ACTION_RULES),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Not exported: only the system and this app deliver the rule alarm, boot and clock broadcasts. */
class LightsAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Lights re-checks the role and re-arms the next rule; nothing to do here on the OnePlus.
        LightsWork.runRules(context.applicationContext)
    }
}

/** One automation pass. Never retried: a light change minutes late is worse than a missed one. */
class LightsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            when (inputData.getString(LightsWork.TRIGGER)) {
                LightsWork.RULES -> Lights.runRules(applicationContext)
                LightsWork.HOME -> Lights.presence(applicationContext, home = true)
                LightsWork.AWAY -> Lights.presence(applicationContext, home = false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HomeDJLights", "Lights automation failed: " + failure.javaClass.simpleName)
        }
        return Result.success()
    }
}
