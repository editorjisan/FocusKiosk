package com.focuskiosk.policy

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.focuskiosk.service.FocusNotificationService
import com.focuskiosk.storage.SecureStorage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * WorkWindowScheduler
 * ───────────────────
 * Manages scheduled daily or periodic (every 1, 2, or 3 days) windows
 * where content creators and video editors can access video uploading and work apps
 * (e.g. YouTube, TikTok, Instagram, Drive, Chrome) without breaking the long-term focus lock.
 *
 * Once the window expires, apps are automatically re-suspended, re-hidden, and notifications suppressed.
 */
object WorkWindowScheduler {

    private const val TAG = "WorkWindowScheduler"
    private const val CHANNEL_ID = "focus_work_window_channel"
    private const val NOTIF_ID = 2002

    const val ACTION_WORK_WINDOW_START = "com.focuskiosk.policy.ACTION_WORK_WINDOW_START"
    const val ACTION_WORK_WINDOW_END   = "com.focuskiosk.policy.ACTION_WORK_WINDOW_END"

    private const val REQUEST_CODE_START = 3001
    private const val REQUEST_CODE_END   = 3002

    fun isEnabled(context: Context): Boolean =
        SecureStorage.getBoolean(context, SecureStorage.KEY_WORK_WINDOW_ENABLED, false)

    fun isWindowActive(context: Context): Boolean =
        SecureStorage.getBoolean(context, SecureStorage.KEY_WORK_WINDOW_ACTIVE, false)

    /**
     * Schedules the next upload / work window based on saved preferences.
     */
    fun scheduleNextWindow(context: Context) {
        if (!isEnabled(context) || !SecureStorage.isLockActive(context)) {
            Log.d(TAG, "Work window not enabled or lock inactive; skipping scheduling.")
            return
        }

        val appContext = context.applicationContext
        val startHour = SecureStorage.getInt(appContext, SecureStorage.KEY_WORK_WINDOW_START_HOUR, 20)
        val startMinute = SecureStorage.getInt(appContext, SecureStorage.KEY_WORK_WINDOW_START_MINUTE, 0)
        val intervalDays = SecureStorage.getInt(appContext, SecureStorage.KEY_WORK_WINDOW_INTERVAL_DAYS, 1).coerceAtLeast(1)

        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, startHour)
            set(Calendar.MINUTE, startMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // If time today has already passed, advance by intervalDays
        if (calendar.timeInMillis <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, intervalDays)
        }

        val nextStartMs = calendar.timeInMillis
        SecureStorage.putLong(appContext, SecureStorage.KEY_NEXT_WINDOW_START_MS, nextStartMs)

        val durationMins = SecureStorage.getInt(appContext, SecureStorage.KEY_WORK_WINDOW_DURATION_MINS, 60).coerceAtLeast(10)
        val nextEndMs = nextStartMs + (durationMins * 60_000L)
        SecureStorage.putLong(appContext, SecureStorage.KEY_NEXT_WINDOW_END_MS, nextEndMs)

        val timeFormat = SimpleDateFormat("EEE, MMM d  h:mm a", Locale.getDefault())
        Log.i(TAG, "Scheduled next work window for: ${timeFormat.format(Date(nextStartMs))} (in ${(nextStartMs - now) / 60000} mins)")

        setExactAlarm(appContext, nextStartMs, ACTION_WORK_WINDOW_START, REQUEST_CODE_START)
    }

    /**
     * Executes when the scheduled upload window starts.
     * Temporarily restores access to allowed work/upload apps.
     */
    fun onWindowStart(context: Context) {
        val appContext = context.applicationContext
        if (!SecureStorage.isLockActive(appContext)) return

        Log.i(TAG, "=======================================================")
        Log.i(TAG, "WORK WINDOW STARTED: Temporarily unlocking upload apps!")
        Log.i(TAG, "=======================================================")

        SecureStorage.putBoolean(appContext, SecureStorage.KEY_WORK_WINDOW_ACTIVE, true)

        val durationMins = SecureStorage.getInt(appContext, SecureStorage.KEY_WORK_WINDOW_DURATION_MINS, 60).coerceAtLeast(10)
        val endMs = System.currentTimeMillis() + (durationMins * 60_000L)
        SecureStorage.putLong(appContext, SecureStorage.KEY_NEXT_WINDOW_END_MS, endMs)

        // Determine packages to unlock
        val workPackages = getAppsToUnlock(appContext)
        Log.i(TAG, "Unlocking ${workPackages.size} apps for work window.")

        // Unsuspend and unhide designated work apps
        PolicyEnforcer.unsuspendPackages(appContext, workPackages.toTypedArray())
        workPackages.forEach { pkg ->
            PolicyEnforcer.unhideApplication(appContext, pkg)
        }

        // Restore notifications for these apps during window
        PolicyEnforcer.restoreNotifications(appContext, workPackages.toList())

        // Post notification informing user of open window
        val endFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        showNotification(
            appContext,
            "Upload & Work Window Active",
            "Apps unlocked until ${endFormat.format(Date(endMs))} for video uploading. Will re-lock automatically."
        )

        // Schedule exact end alarm
        setExactAlarm(appContext, endMs, ACTION_WORK_WINDOW_END, REQUEST_CODE_END)
    }

    /**
     * Executes when the scheduled upload window ends.
     * Automatically re-suspends and re-hides work apps.
     */
    fun onWindowEnd(context: Context) {
        val appContext = context.applicationContext
        if (!SecureStorage.isLockActive(appContext)) return

        Log.i(TAG, "=======================================================")
        Log.i(TAG, "WORK WINDOW ENDED: Automatically re-locking apps!")
        Log.i(TAG, "=======================================================")

        SecureStorage.putBoolean(appContext, SecureStorage.KEY_WORK_WINDOW_ACTIVE, false)

        val workPackages = getAppsToUnlock(appContext)

        // Re-suspend and re-hide
        PolicyEnforcer.suspendPackages(appContext, workPackages.toTypedArray())
        workPackages.forEach { pkg ->
            PolicyEnforcer.hideApplication(appContext, pkg)
        }

        // Re-suppress notifications
        PolicyEnforcer.suppressNotifications(appContext, workPackages.toList())

        showNotification(
            appContext,
            "Upload Window Closed",
            "Focus Lock re-enforced automatically. Good job staying focused!"
        )

        // Schedule the next periodic window
        scheduleNextWindow(appContext)
    }

    /**
     * Validates current time against window intervals (called on wake / boot).
     */
    fun checkAndSyncWindow(context: Context) {
        if (!isEnabled(context) || !SecureStorage.isLockActive(context)) return

        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val startMs = SecureStorage.getLong(appContext, SecureStorage.KEY_NEXT_WINDOW_START_MS, 0L)
        val endMs = SecureStorage.getLong(appContext, SecureStorage.KEY_NEXT_WINDOW_END_MS, 0L)
        val isCurrentlyActive = isWindowActive(appContext)

        if (startMs in 1..now && now < endMs) {
            // Inside active window
            if (!isCurrentlyActive) {
                onWindowStart(appContext)
            }
        } else if (now >= endMs && isCurrentlyActive && endMs > 0L) {
            // Window ended
            onWindowEnd(appContext)
        } else if (!isCurrentlyActive && (startMs == 0L || startMs < now)) {
            // Need fresh schedule
            scheduleNextWindow(appContext)
        }
    }

    private fun getAppsToUnlock(context: Context): Set<String> {
        val configured = SecureStorage.getPackageSet(context, SecureStorage.KEY_WORK_WINDOW_PACKAGES)
        if (configured.isNotEmpty()) return configured

        // Default creator / work app package set if none explicitly configured:
        // YouTube, TikTok, Instagram, Facebook, Google Drive, Chrome
        val blocked = SecureStorage.getBlockedPackages(context)
        val defaultUploadKeywords = listOf("youtube", "tiktok", "instagram", "facebook", "drive", "chrome", "phoenix", "premiere")
        val matched = blocked.filter { pkg ->
            defaultUploadKeywords.any { pkg.contains(it, ignoreCase = true) }
        }.toSet()

        return if (matched.isNotEmpty()) matched else blocked
    }

    private fun setExactAlarm(context: Context, triggerMs: Long, action: String, requestCode: Int) {
        runCatching {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WorkWindowReceiver::class.java).apply {
                this.action = action
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            }
            Log.d(TAG, "Exact alarm set for action $action at $triggerMs")
        }.onFailure { Log.e(TAG, "Failed setting alarm for $action: ${it.message}") }
    }

    private fun showNotification(context: Context, title: String, message: String) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Focus Kiosk Work Window",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for scheduled video upload and work windows"
                }
                nm.createNotificationChannel(channel)
            }

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.focuskiosk.R.mipmap.ic_launcher)
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
