package com.focuskiosk.policy

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.focuskiosk.storage.SecureStorage
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/**
 * WorkWindowScheduler
 * ───────────────────
 * The "Upload Window": every N days at a fixed time the phone is FULLY unlocked for a set
 * length of time (all apps, notifications and websites), then the focus lock is re-applied
 * automatically. Windows are counted from a fixed anchor day so the cadence never drifts,
 * windows that run past midnight are handled, and each window opens at most once.
 */
object WorkWindowScheduler {

    private const val TAG = "WorkWindowScheduler"
    private const val CHANNEL_ID = "focus_work_window_channel"
    private const val NOTIF_ID = 2002

    const val ACTION_WORK_WINDOW_START = "com.focuskiosk.policy.ACTION_WORK_WINDOW_START"
    const val ACTION_WORK_WINDOW_END   = "com.focuskiosk.policy.ACTION_WORK_WINDOW_END"

    private const val REQUEST_CODE_START = 3001
    private const val REQUEST_CODE_END   = 3002

    const val DEFAULT_START_HOUR = 20
    const val DEFAULT_DURATION_MINS = 60
    const val MAX_INTERVAL_DAYS = 30

    data class WindowSchedule(
        val isCurrentlyActive: Boolean,
        val currentWindowStartMs: Long,
        val currentWindowEndMs: Long,
        val nextWindowStartMs: Long,
        val nextWindowEndMs: Long
    )

    data class Config(val hour: Int, val minute: Int, val intervalDays: Int, val durationMins: Int)

    fun config(context: Context) = Config(
        hour = SecureStorage.getInt(context, SecureStorage.KEY_WORK_WINDOW_START_HOUR, DEFAULT_START_HOUR).coerceIn(0, 23),
        minute = SecureStorage.getInt(context, SecureStorage.KEY_WORK_WINDOW_START_MINUTE, 0).coerceIn(0, 59),
        intervalDays = SecureStorage.getInt(context, SecureStorage.KEY_WORK_WINDOW_INTERVAL_DAYS, 1).coerceIn(1, MAX_INTERVAL_DAYS),
        durationMins = SecureStorage.getInt(context, SecureStorage.KEY_WORK_WINDOW_DURATION_MINS, DEFAULT_DURATION_MINS).coerceIn(5, 24 * 60)
    )

    fun saveConfig(context: Context, cfg: Config) {
        SecureStorage.putInt(context, SecureStorage.KEY_WORK_WINDOW_START_HOUR, cfg.hour)
        SecureStorage.putInt(context, SecureStorage.KEY_WORK_WINDOW_START_MINUTE, cfg.minute)
        SecureStorage.putInt(context, SecureStorage.KEY_WORK_WINDOW_INTERVAL_DAYS, cfg.intervalDays)
        SecureStorage.putInt(context, SecureStorage.KEY_WORK_WINDOW_DURATION_MINS, cfg.durationMins)
    }

    fun isEnabled(context: Context): Boolean =
        SecureStorage.getBoolean(context, SecureStorage.KEY_WORK_WINDOW_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        SecureStorage.putBoolean(context, SecureStorage.KEY_WORK_WINDOW_ENABLED, enabled)

    fun isWindowActive(context: Context): Boolean =
        SecureStorage.getBoolean(context, SecureStorage.KEY_WORK_WINDOW_ACTIVE, false)

    private fun cycleId(startMs: Long) = "cycle_$startMs"

    /** Re-anchors the cadence on today (called when the focus lock starts). */
    fun resetAnchor(context: Context, now: Long = System.currentTimeMillis()) {
        SecureStorage.putLong(context, SecureStorage.KEY_WORK_WINDOW_ANCHOR_MS, now)
        SecureStorage.putString(context, SecureStorage.KEY_WORK_WINDOW_CYCLE_ID, "")
    }

    /** Starting a lock inside today's window should not unlock straight away; skip to the next one. */
    fun skipCurrentWindow(context: Context) {
        val schedule = calculateWindowSchedule(context)
        if (schedule.isCurrentlyActive) {
            SecureStorage.putString(context, SecureStorage.KEY_WORK_WINDOW_CYCLE_ID, cycleId(schedule.currentWindowStartMs))
        }
    }

    private fun anchorDate(context: Context, zone: ZoneId, now: Long): LocalDate {
        var anchorMs = SecureStorage.getLong(context, SecureStorage.KEY_WORK_WINDOW_ANCHOR_MS, 0L)
        if (anchorMs <= 0L) {
            anchorMs = now
            SecureStorage.putLong(context, SecureStorage.KEY_WORK_WINDOW_ANCHOR_MS, anchorMs)
        }
        return Instant.ofEpochMilli(anchorMs).atZone(zone).toLocalDate()
    }

    /** Pure schedule maths, exposed for previews in the UI. */
    fun computeSchedule(cfg: Config, anchor: LocalDate, finishedCycleId: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): WindowSchedule {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val durationMs = cfg.durationMins * 60_000L
        val lookBack = (cfg.durationMins / (24 * 60)) + 1
        var active: Pair<Long, Long>? = null
        var next: Pair<Long, Long>? = null
        for (offset in -lookBack.toLong()..(cfg.intervalDays + 1).toLong()) {
            val date = today.plusDays(offset)
            if (Math.floorMod(ChronoUnit.DAYS.between(anchor, date), cfg.intervalDays.toLong()) != 0L) continue
            val start = date.atTime(cfg.hour, cfg.minute).atZone(zone).toInstant().toEpochMilli()
            val end = start + durationMs
            if (active == null && now in start until end && cycleId(start) != finishedCycleId) active = start to end
            if (next == null && start > now) next = start to end
        }
        val n = next ?: (now + cfg.intervalDays * 86_400_000L).let { it to it + durationMs }
        return WindowSchedule(
            isCurrentlyActive = active != null,
            currentWindowStartMs = active?.first ?: 0L,
            currentWindowEndMs = active?.second ?: 0L,
            nextWindowStartMs = n.first,
            nextWindowEndMs = n.second
        )
    }

    fun calculateWindowSchedule(context: Context, now: Long = System.currentTimeMillis()): WindowSchedule {
        val zone = ZoneId.systemDefault()
        return computeSchedule(
            config(context),
            anchorDate(context, zone, now),
            SecureStorage.getString(context, SecureStorage.KEY_WORK_WINDOW_CYCLE_ID, ""),
            now,
            zone
        )
    }

    /** Arms the alarm for the next window (or opens the current one if we are inside it). */
    fun scheduleNextWindow(context: Context) {
        val appContext = context.applicationContext
        if (!isEnabled(appContext) || !SecureStorage.isLockActive(appContext)) {
            cancelAlarm(appContext, ACTION_WORK_WINDOW_START, REQUEST_CODE_START)
            return
        }
        val now = System.currentTimeMillis()
        val schedule = calculateWindowSchedule(appContext, now)
        if (schedule.isCurrentlyActive) {
            if (!isWindowActive(appContext)) onWindowStart(appContext)
            return
        }
        SecureStorage.putLong(appContext, SecureStorage.KEY_NEXT_WINDOW_START_MS, schedule.nextWindowStartMs)
        SecureStorage.putLong(appContext, SecureStorage.KEY_NEXT_WINDOW_END_MS, schedule.nextWindowEndMs)
        setExactAlarm(appContext, schedule.nextWindowStartMs, ACTION_WORK_WINDOW_START, REQUEST_CODE_START)
        Log.i(TAG, "Next upload window: ${fmt(schedule.nextWindowStartMs)} → ${fmt(schedule.nextWindowEndMs)}")
    }

    /** Fully unlocks the phone for the current window. */
    fun onWindowStart(context: Context) {
        val appContext = context.applicationContext
        if (!SecureStorage.isLockActive(appContext) || !isEnabled(appContext)) return

        // The start alarm may fire a few ms early; look a minute ahead for the window.
        val now = System.currentTimeMillis()
        var schedule = calculateWindowSchedule(appContext, now)
        if (!schedule.isCurrentlyActive) schedule = calculateWindowSchedule(appContext, now + 60_000L)
        if (!schedule.isCurrentlyActive) {
            scheduleNextWindow(appContext)
            return
        }

        Log.i(TAG, "UPLOAD WINDOW OPEN until ${fmt(schedule.currentWindowEndMs)} — unlocking everything.")
        SecureStorage.putBoolean(appContext, SecureStorage.KEY_WORK_WINDOW_ACTIVE, true)
        SecureStorage.putLong(appContext, SecureStorage.KEY_ACTIVE_WINDOW_START_MS, schedule.currentWindowStartMs)
        SecureStorage.putLong(appContext, SecureStorage.KEY_NEXT_WINDOW_END_MS, schedule.currentWindowEndMs)

        val blocked = SecureStorage.getBlockedPackages(appContext)
        PolicyEnforcer.unsuspendPackages(appContext, blocked.toTypedArray())
        blocked.forEach { PolicyEnforcer.unhideApplication(appContext, it) }
        PolicyEnforcer.restoreNotifications(appContext, blocked.toList())
        PolicyEnforcer.clearWebFiltering(appContext)

        showNotification(
            appContext,
            "Upload Window Open",
            "Your phone is fully unlocked until ${timeFmt(schedule.currentWindowEndMs)}. It locks again automatically."
        )
        setExactAlarm(appContext, schedule.currentWindowEndMs, ACTION_WORK_WINDOW_END, REQUEST_CODE_END)
    }

    /**
     * Closes the window and re-applies the full focus lock (re-computed from the allowed apps,
     * so anything installed during the window is locked too).
     * @param isManualEarlyDone true when the user tapped "I'm Done — Lock Now".
     */
    fun onWindowEnd(context: Context, isManualEarlyDone: Boolean = false) {
        val appContext = context.applicationContext
        val activeStart = SecureStorage.getLong(appContext, SecureStorage.KEY_ACTIVE_WINDOW_START_MS, 0L)
        if (activeStart > 0L) {
            SecureStorage.putString(appContext, SecureStorage.KEY_WORK_WINDOW_CYCLE_ID, cycleId(activeStart))
        }
        SecureStorage.putBoolean(appContext, SecureStorage.KEY_WORK_WINDOW_ACTIVE, false)
        SecureStorage.putLong(appContext, SecureStorage.KEY_ACTIVE_WINDOW_START_MS, 0L)
        cancelAlarm(appContext, ACTION_WORK_WINDOW_END, REQUEST_CODE_END)

        if (!SecureStorage.isLockActive(appContext)) return

        Log.i(TAG, "UPLOAD WINDOW CLOSED (manual=$isManualEarlyDone) — re-locking.")
        PolicyEnforcer.activateFocusLock(appContext)

        showNotification(
            appContext,
            "Upload Window Closed",
            if (isManualEarlyDone) "Locked again. Nice work." else "Time's up — Focus Lock is back on."
        )
        scheduleNextWindow(appContext)
    }

    /** Called on boot, screen wake and from the UI ticker; idempotent. */
    fun checkAndSyncWindow(context: Context) {
        val appContext = context.applicationContext
        if (!SecureStorage.isLockActive(appContext)) return
        if (!isEnabled(appContext)) {
            if (isWindowActive(appContext)) onWindowEnd(appContext)
            return
        }
        val schedule = calculateWindowSchedule(appContext)
        val flagged = isWindowActive(appContext)
        when {
            schedule.isCurrentlyActive && !flagged -> onWindowStart(appContext)
            !schedule.isCurrentlyActive && flagged -> onWindowEnd(appContext)
            else -> scheduleNextWindow(appContext)
        }
    }

    fun cancelAll(context: Context) {
        cancelAlarm(context, ACTION_WORK_WINDOW_START, REQUEST_CODE_START)
        cancelAlarm(context, ACTION_WORK_WINDOW_END, REQUEST_CODE_END)
    }

    private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, WorkWindowReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun cancelAlarm(context: Context, action: String, requestCode: Int) {
        runCatching {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(context, action, requestCode))
        }
    }

    private fun setExactAlarm(context: Context, triggerMs: Long, action: String, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = pendingIntent(context, action, requestCode)
        runCatching {
            // Exact + allowed in Doze, without taking over the status-bar alarm icon used by real alarms.
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, operation)
        }.onFailure {
            Log.w(TAG, "Exact alarm refused (${it.message}); using setAlarmClock.")
            runCatching {
                val show = PendingIntent.getActivity(
                    context, requestCode + 100,
                    Intent(context, com.focuskiosk.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerMs, show), operation)
            }
        }
    }

    private fun fmt(ms: Long) = SimpleDateFormat("EEE, MMM d h:mm a", Locale.getDefault()).format(Date(ms))
    private fun timeFmt(ms: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))

    private fun showNotification(context: Context, title: String, message: String) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Upload Window", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When your phone unlocks and locks again for uploads"
                }
            )
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.focuskiosk.R.drawable.ic_sym_upload)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, notif)
        }.onFailure { Log.w(TAG, "Failed posting notification: ${it.message}") }
    }
}
