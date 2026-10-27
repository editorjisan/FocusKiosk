package com.focuskiosk.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.focuskiosk.storage.SecureStorage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * ExtremeAlarmManager
 * ───────────────────
 * High-priority wake-up alarm scheduler.
 *
 * Uses AlarmManager.setAlarmClock() which guarantees the Android kernel
 * wakes up from deep Doze mode, displays the clock icon on the lock screen,
 * and launches the extreme alarm screen.
 */
object ExtremeAlarmManager {

    private const val TAG = "ExtremeAlarmManager"
    const val ACTION_ALARM_TRIGGER = "com.focuskiosk.alarm.ACTION_EXTREME_ALARM_TRIGGER"
    private const val REQUEST_CODE_ALARM = 4001

    fun isAlarmEnabled(context: Context): Boolean =
        SecureStorage.getBoolean(context, SecureStorage.KEY_ALARM_ENABLED, false)

    /**
     * Calculates the next epoch timestamp for the scheduled alarm.
     */
    fun calculateNextAlarmMs(context: Context): Long {
        val alarms = getAlarms(context).filter { it.isEnabled }
        if (alarms.isEmpty()) {
            val hour = SecureStorage.getInt(context, SecureStorage.KEY_ALARM_HOUR, 6)
            val minute = SecureStorage.getInt(context, SecureStorage.KEY_ALARM_MINUTE, 30)
            return calculateTimestampForTime(hour, minute)
        }

        val now = System.currentTimeMillis()
        var earliest = Long.MAX_VALUE
        for (a in alarms) {
            val ts = calculateTimestampForTime(a.hour, a.minute)
            if (ts < earliest) {
                earliest = ts
            }
        }
        return if (earliest == Long.MAX_VALUE) calculateTimestampForTime(6, 30) else earliest
    }

    private fun calculateTimestampForTime(hour: Int, minute: Int): Long {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    fun getAlarms(context: Context): List<AlarmItem> {
        val raw = SecureStorage.getString(context, SecureStorage.KEY_MULTI_ALARMS_JSON, "")
        if (raw.isBlank()) {
            val defaultHour = SecureStorage.getInt(context, SecureStorage.KEY_ALARM_HOUR, 6)
            val defaultMin = SecureStorage.getInt(context, SecureStorage.KEY_ALARM_MINUTE, 30)
            val defaultList = listOf(AlarmItem(hour = defaultHour, minute = defaultMin, label = "Wake up", isEnabled = true))
            saveAlarms(context, defaultList)
            return defaultList
        }
        return AlarmItem.listFromJsonString(raw)
    }

    fun saveAlarms(context: Context, list: List<AlarmItem>) {
        SecureStorage.putString(context, SecureStorage.KEY_MULTI_ALARMS_JSON, AlarmItem.listToJsonString(list))
        val nextActive = list.firstOrNull { it.isEnabled }
        if (nextActive != null) {
            SecureStorage.putInt(context, SecureStorage.KEY_ALARM_HOUR, nextActive.hour)
            SecureStorage.putInt(context, SecureStorage.KEY_ALARM_MINUTE, nextActive.minute)
        }
    }

    /**
     * Schedules the next exact hardware alarm.
     */
    fun scheduleAlarm(context: Context) {
        val appContext = context.applicationContext
        if (!isAlarmEnabled(appContext)) {
            Log.d(TAG, "Extreme alarm disabled; skipping schedule.")
            cancelAlarm(appContext)
            return
        }

        val triggerMs = calculateNextAlarmMs(appContext)
        SecureStorage.putLong(appContext, SecureStorage.KEY_NEXT_ALARM_TIMESTAMP_MS, triggerMs)

        val timeFormat = SimpleDateFormat("EEE, MMM d  h:mm a", Locale.getDefault())
        Log.i(TAG, "Scheduling Extreme Alarm for: ${timeFormat.format(Date(triggerMs))} (in ${(triggerMs - System.currentTimeMillis()) / 60000} mins)")

        runCatching {
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val intent = Intent(appContext, ExtremeAlarmReceiver::class.java).apply {
                action = ACTION_ALARM_TRIGGER
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                REQUEST_CODE_ALARM,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Show intent when clicking the system alarm clock icon on lock screen
            val showIntent = Intent(appContext, ExtremeAlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingShowIntent = PendingIntent.getActivity(
                appContext,
                REQUEST_CODE_ALARM + 1,
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerMs, pendingShowIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)

            Log.i(TAG, "Hardware AlarmClock set successfully for $triggerMs")
        }.onFailure { Log.e(TAG, "Failed setting alarm: ${it.message}", it) }
    }

    /**
     * Cancels any scheduled alarms.
     */
    fun cancelAlarm(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(appContext, ExtremeAlarmReceiver::class.java).apply {
                action = ACTION_ALARM_TRIGGER
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                REQUEST_CODE_ALARM,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
            SecureStorage.putLong(appContext, SecureStorage.KEY_NEXT_ALARM_TIMESTAMP_MS, 0L)
            Log.i(TAG, "Extreme Alarm cancelled.")
        }
    }

    /**
     * Reschedules alarm after dismissal for the next day.
     */
    fun onAlarmDismissed(context: Context) {
        val appContext = context.applicationContext
        Log.i(TAG, "Alarm dismissed! Rescheduling for next cycle.")
        SecureStorage.putBoolean(appContext, SecureStorage.KEY_ALARM_RINGING, false)
        scheduleAlarm(appContext)
    }

    /**
     * Verifies alarm state on boot or screen unlock.
     */
    fun checkAndSyncAlarm(context: Context) {
        val appContext = context.applicationContext
        if (!isAlarmEnabled(appContext)) return

        val now = System.currentTimeMillis()
        val triggerMs = SecureStorage.getLong(appContext, SecureStorage.KEY_NEXT_ALARM_TIMESTAMP_MS, 0L)

        if (triggerMs <= 0L || triggerMs < now) {
            scheduleAlarm(appContext)
        }
    }
}
