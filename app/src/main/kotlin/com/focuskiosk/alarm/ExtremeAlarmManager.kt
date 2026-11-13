package com.focuskiosk.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * ExtremeAlarmManager
 * ───────────────────
 * Schedules the next alarm with AlarmManager.setAlarmClock(), which wakes the device
 * from Doze and shows the alarm icon in the status bar, exactly like the Clock app.
 * Only the earliest upcoming alarm is armed; it is re-computed after every change,
 * trigger, dismissal, reboot and time-zone change.
 */
object ExtremeAlarmManager {

    private const val TAG = "ExtremeAlarmManager"
    const val ACTION_ALARM_TRIGGER = "com.focuskiosk.alarm.ACTION_EXTREME_ALARM_TRIGGER"
    const val ACTION_RESUME_RINGING = "com.focuskiosk.alarm.ACTION_RESUME_RINGING"
    const val EXTRA_ALARM_ID = "alarm_id"
    private const val REQUEST_CODE_ALARM = 4001
    private const val REQUEST_CODE_RESUME = 4003

    fun getAlarms(context: Context): List<AlarmItem> = AlarmStore.getAlarms(context)

    fun hasEnabledAlarms(context: Context): Boolean = getAlarms(context).any { it.isEnabled }

    fun saveAlarms(context: Context, list: List<AlarmItem>) {
        AlarmStore.saveAlarms(context, list)
        scheduleNext(context)
    }

    fun upsert(context: Context, item: AlarmItem) {
        val list = getAlarms(context).filter { it.id != item.id } + item
        saveAlarms(context, list)
    }

    fun delete(context: Context, id: String) = saveAlarms(context, getAlarms(context).filter { it.id != id })

    fun setEnabled(context: Context, id: String, enabled: Boolean) =
        saveAlarms(context, getAlarms(context).map { if (it.id == id) it.copy(isEnabled = enabled) else it })

    /** Next time [item] rings strictly after [fromMs]. */
    fun nextTrigger(item: AlarmItem, fromMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = fromMs
            set(Calendar.HOUR_OF_DAY, item.hour)
            set(Calendar.MINUTE, item.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        for (i in 0..7) {
            if (cal.timeInMillis > fromMs && (!item.isRepeating || cal.get(Calendar.DAY_OF_WEEK) in item.days)) {
                return cal.timeInMillis
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    fun nextAlarm(context: Context, fromMs: Long = System.currentTimeMillis()): Pair<AlarmItem, Long>? =
        getAlarms(context).filter { it.isEnabled }
            .map { it to nextTrigger(it, fromMs) }
            .minByOrNull { it.second }

    private fun triggerIntent(context: Context, alarmId: String?): PendingIntent {
        val intent = Intent(context, ExtremeAlarmReceiver::class.java).apply {
            action = ACTION_ALARM_TRIGGER
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE_ALARM, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun scheduleNext(context: Context, fromMs: Long = System.currentTimeMillis()) {
        val appContext = context.applicationContext
        val next = nextAlarm(appContext, fromMs)
        com.focuskiosk.cloud.FkCloud.report(appContext)
        if (next == null) {
            cancelAlarm(appContext)
            return
        }
        val (item, triggerMs) = next
        AlarmStore.setNextAlarm(appContext, item.id, triggerMs)
        runCatching {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val show = PendingIntent.getActivity(
                appContext, REQUEST_CODE_ALARM + 1,
                Intent(appContext, com.focuskiosk.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerMs, show), triggerIntent(appContext, item.id))
            val fmt = SimpleDateFormat("EEE, MMM d h:mm a", Locale.getDefault())
            Log.i(TAG, "Next alarm '${item.label}' at ${fmt.format(Date(triggerMs))}")
        }.onFailure { Log.e(TAG, "Failed setting alarm: ${it.message}", it) }
    }

    fun cancelAlarm(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(triggerIntent(appContext, null))
        }
        AlarmStore.setNextAlarm(appContext, null, 0L)
    }

    /** Called by the receiver when the hardware alarm fires. */
    fun onTriggered(context: Context, alarmId: String?) {
        val appContext = context.applicationContext
        val item = getAlarms(appContext).firstOrNull { it.id == alarmId }
        if (item != null && !item.isRepeating) {
            AlarmStore.saveAlarms(appContext, getAlarms(appContext).map { if (it.id == item.id) it.copy(isEnabled = false) else it })
        }
        startRinging(appContext, alarmId)
        // Arm the following alarm right away; skip the minute that is ringing now.
        scheduleNext(appContext, System.currentTimeMillis() + 60_000L)
    }

    fun startRinging(context: Context, alarmId: String?) {
        val appContext = context.applicationContext
        AlarmStore.setRinging(appContext, true, alarmId ?: AlarmStore.ringingAlarmId(appContext))
        AlarmStore.ensureOneTimePassword(appContext)
        ExtremeAlarmService.start(appContext)
        ExtremeAlarmService.launchAlarmScreen(appContext)
    }

    fun onDismissed(context: Context) {
        val appContext = context.applicationContext
        Log.i(TAG, "Alarm dismissed with PC code.")
        AlarmStore.setRinging(appContext, false)
        AlarmStore.clearOneTimePassword(appContext)
        cancelResume(appContext)
        ExtremeAlarmService.stop(appContext)
        scheduleNext(appContext)
    }

    /**
     * Re-arms after boot / time change, and resumes an alarm that was still ringing
     * when the phone was force-restarted.
     */
    fun checkAndSync(context: Context, resumeRinging: Boolean) {
        val appContext = context.applicationContext
        if (resumeRinging && AlarmStore.isRinging(appContext)) {
            Log.w(TAG, "Alarm was ringing before restart — resuming.")
            startRinging(appContext, null)
        }
        scheduleNext(appContext)
    }

    /** Safety net: if the ringing service dies, bring it back in a few seconds. */
    fun scheduleResume(context: Context, delayMs: Long = 3_000L) {
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, resumeIntent(context))
        }
    }

    private fun cancelResume(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(resumeIntent(context))
        }
    }

    private fun resumeIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE_RESUME,
        Intent(context, ExtremeAlarmReceiver::class.java).setAction(ACTION_RESUME_RINGING),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
