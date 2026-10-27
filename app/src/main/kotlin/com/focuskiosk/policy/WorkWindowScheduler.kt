package com.focuskiosk.policy

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
 * (e.g. YouTube, TikTok, Instagram, Drive, Chrome, CapCut) without breaking the long-term focus lock.
 *
 * Guarantees:
 * 1. Deterministic Multi-day Intervals: Uses fixed anchor epoch to prevent schedule drift.
 * 2. Active Window Detection: If rebooted or launched within a window, immediately activates.
 * 3. Web Filtering Synchronization: Clears URLBlocklist during window so creators can upload
 *    videos to Facebook, TikTok, Instagram, and re-enforces URLBlocklist when window closes.
 * 4. Desktop Icon Preservation: Suspends/unsuspends apps WITHOUT hiding, keeping desktop icons intact.
 * 5. Hardware RTC Wake-up: Uses setAlarmClock() so alarms fire on time even in deep Doze mode.
 */
object WorkWindowScheduler {

    private const val TAG = "WorkWindowScheduler"
    private const val CHANNEL_ID = "focus_work_window_channel"
    private const val NOTIF_ID = 2002

    const val ACTION_WORK_WINDOW_START = "com.focuskiosk.policy.ACTION_WORK_WINDOW_START"
    const val ACTION_WORK_WINDOW_END   = "com.focuskiosk.policy.ACTION_WORK_WINDOW_END"

    private const val REQUEST_CODE_START = 3001
    private const val REQUEST_CODE_END   = 3002

    data class WindowSchedule(
        val isCurrentlyActive: Boolean,
        val currentWindowEndMs: Long,
        val nextWindowStartMs: Long,
        val nextWindowEndMs: Long
    )

    fun isEnabled(context: Context): Boolean =
        SecureStorage.getBoolean(context, SecureStorage.KEY_WORK_WINDOW_ENABLED, false)

    fun isWindowActive(context: Context): Boolean =
        SecureStorage.getBoolean(context, SecureStorage.KEY_WORK_WINDOW_ACTIVE, false)

    /**
     * Calculates the deterministic window schedule relative to the fixed anchor date.
     */
    fun calculateWindowSchedule(context: Context, now: Long = System.currentTimeMillis()): WindowSchedule {
        val startHour = SecureStorage.getInt(context, SecureStorage.KEY_WORK_WINDOW_START_HOUR, 20)
        val startMinute = SecureStorage.getInt(context, SecureStorage.KEY_WORK_WINDOW_START_MINUTE, 0)
        val intervalDays = SecureStorage.getInt(context, SecureStorage.KEY_WORK_WINDOW_INTERVAL_DAYS, 1).coerceAtLeast(1)
        val durationMins = SecureStorage.getInt(context, SecureStorage.KEY_WORK_WINDOW_DURATION_MINS, 60).coerceAtLeast(10)
        val durationMs = durationMins * 60_000L

        // Retrieve or initialize fixed anchor date
        var anchorMs = SecureStorage.getLong(context, SecureStorage.KEY_WORK_WINDOW_ANCHOR_MS, 0L)
        if (anchorMs <= 0L) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, startHour)
                set(Calendar.MINUTE, startMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            anchorMs = cal.timeInMillis
            SecureStorage.putLong(context, SecureStorage.KEY_WORK_WINDOW_ANCHOR_MS, anchorMs)
        }

        // Anchor midnight
        val anchorZero = Calendar.getInstance().apply {
            timeInMillis = anchorMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Today midnight
        val todayZero = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val diffDays = ((todayZero.timeInMillis - anchorZero.timeInMillis) / (24L * 3600_000L)).toInt()

        // Today's scheduled window start and end
        val todayWindowStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, startHour)
            set(Calendar.MINUTE, startMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val todayWindowEnd = todayWindowStart + durationMs

        // Check if today matches the interval cadence
        val isTodayIntervalDay = (diffDays % intervalDays == 0)

        // Generate unique cycle ID for today's scheduled window (e.g. "cycle_2026-10-27_20_00")
        val cycleDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(todayWindowStart))
        val currentCycleId = "cycle_${cycleDateStr}_${startHour}_${startMinute}"
        val lastFinishedCycleId = SecureStorage.getString(context, SecureStorage.KEY_WORK_WINDOW_CYCLE_ID, "")

        // If this specific cycle was already finished (by time expiration or user clicking "Video Upload Done"), do not reactivate it today!
        val isCycleAlreadyFinished = (lastFinishedCycleId == currentCycleId)

        return if (isTodayIntervalDay) {
            if (now in todayWindowStart until todayWindowEnd && !isCycleAlreadyFinished) {
                // ACTIVE NOW!
                val nextStartCal = Calendar.getInstance().apply {
                    timeInMillis = todayWindowStart
                    add(Calendar.DAY_OF_YEAR, intervalDays)
                }
                WindowSchedule(
                    isCurrentlyActive = true,
                    currentWindowEndMs = todayWindowEnd,
                    nextWindowStartMs = nextStartCal.timeInMillis,
                    nextWindowEndMs = nextStartCal.timeInMillis + durationMs
                )
            } else if (now < todayWindowStart) {
                // Scheduled for later TODAY
                WindowSchedule(
                    isCurrentlyActive = false,
                    currentWindowEndMs = 0L,
                    nextWindowStartMs = todayWindowStart,
                    nextWindowEndMs = todayWindowEnd
                )
            } else {
                // Today's window has already passed or was finished; advance by intervalDays
                val nextStartCal = Calendar.getInstance().apply {
                    timeInMillis = todayWindowStart
                    add(Calendar.DAY_OF_YEAR, intervalDays)
                }
                WindowSchedule(
                    isCurrentlyActive = false,
                    currentWindowEndMs = 0L,
                    nextWindowStartMs = nextStartCal.timeInMillis,
                    nextWindowEndMs = nextStartCal.timeInMillis + durationMs
                )
            }
        } else {
            // Today is an off-day in the multi-day interval
            val mod = ((diffDays % intervalDays) + intervalDays) % intervalDays
            val daysUntilNext = intervalDays - mod
            val nextStartCal = Calendar.getInstance().apply {
                timeInMillis = todayWindowStart
                add(Calendar.DAY_OF_YEAR, daysUntilNext)
            }
            WindowSchedule(
                isCurrentlyActive = false,
                currentWindowEndMs = 0L,
                nextWindowStartMs = nextStartCal.timeInMillis,
                nextWindowEndMs = nextStartCal.timeInMillis + durationMs
            )
        }
    }

    /**
     * Schedules the next upload / work window based on saved preferences.
     */
    fun scheduleNextWindow(context: Context) {
        if (!isEnabled(context) || !SecureStorage.isLockActive(context)) {
            Log.d(TAG, "Work window not enabled or lock inactive; skipping scheduling.")
            return
        }

        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val schedule = calculateWindowSchedule(appContext, now)

        if (schedule.isCurrentlyActive) {
            onWindowStart(appContext)
            return
        }

        val nextStartMs = schedule.nextWindowStartMs
        val nextEndMs = schedule.nextWindowEndMs
        SecureStorage.putLong(appContext, SecureStorage.KEY_NEXT_WINDOW_START_MS, nextStartMs)
        SecureStorage.putLong(appContext, SecureStorage.KEY_NEXT_WINDOW_END_MS, nextEndMs)

        val timeFormat = SimpleDateFormat("EEE, MMM d  h:mm a", Locale.getDefault())
        Log.i(TAG, "Scheduled next work window for: ${timeFormat.format(Date(nextStartMs))} (in ${(nextStartMs - now) / 60000} mins)")

        setExactAlarm(appContext, nextStartMs, ACTION_WORK_WINDOW_START, REQUEST_CODE_START)
    }

    /**
     * Executes when the scheduled upload window starts.
     * Temporarily restores access to allowed work/upload apps and clears web filtering.
     */
    fun onWindowStart(context: Context) {
        val appContext = context.applicationContext
        if (!SecureStorage.isLockActive(appContext)) return

        Log.i(TAG, "=======================================================")
        Log.i(TAG, "WORK WINDOW STARTED: Temporarily unlocking upload apps!")
        Log.i(TAG, "=======================================================")

        SecureStorage.putBoolean(appContext, SecureStorage.KEY_WORK_WINDOW_ACTIVE, true)

        val now = System.currentTimeMillis()
        val schedule = calculateWindowSchedule(appContext, now)
        val endMs = if (schedule.isCurrentlyActive && schedule.currentWindowEndMs > now) {
            schedule.currentWindowEndMs
        } else {
            val durationMins = SecureStorage.getInt(appContext, SecureStorage.KEY_WORK_WINDOW_DURATION_MINS, 60).coerceAtLeast(10)
            now + (durationMins * 60_000L)
        }
        SecureStorage.putLong(appContext, SecureStorage.KEY_NEXT_WINDOW_END_MS, endMs)

        // Determine packages to unlock
        val workPackages = getAppsToUnlock(appContext)
        Log.i(TAG, "Unlocking ${workPackages.size} apps for work window.")

        // Unsuspend designated work apps
        PolicyEnforcer.unsuspendPackages(appContext, workPackages.toTypedArray())

        // Ensure any apps previously hidden in older versions are unhidden
        workPackages.forEach { pkg ->
            PolicyEnforcer.unhideApplication(appContext, pkg)
        }

        // Restore notifications for these apps during window
        PolicyEnforcer.restoreNotifications(appContext, workPackages.toList())

        // CRITICAL: Temporarily clear web filtering so social media & creator platforms can upload!
        PolicyEnforcer.clearWebFiltering(appContext)

        // Post notification informing user of open window
        val endFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        showNotification(
            appContext,
            "Upload & Work Window Active 🟢",
            "Creator apps unlocked until ${endFormat.format(Date(endMs))} for video uploading. Will re-lock automatically."
        )

        // Schedule exact end alarm via setAlarmClock
        setExactAlarm(appContext, endMs, ACTION_WORK_WINDOW_END, REQUEST_CODE_END)
    }

    /**
     * Executes when the scheduled upload window ends.
     * Automatically re-suspends work apps and re-enforces web filtering.
     * @param isManualEarlyDone true if user clicked "Video Upload Done (Lock Now)"
     */
    fun onWindowEnd(context: Context, isManualEarlyDone: Boolean = false) {
        val appContext = context.applicationContext
        if (!SecureStorage.isLockActive(appContext)) return

        Log.i(TAG, "=======================================================")
        Log.i(TAG, "WORK WINDOW ENDED (manual=$isManualEarlyDone): Automatically re-locking apps!")
        Log.i(TAG, "=======================================================")

        SecureStorage.putBoolean(appContext, SecureStorage.KEY_WORK_WINDOW_ACTIVE, false)

        // Mark today's cycle as finished so it doesn't trigger again until the next interval day
        val now = System.currentTimeMillis()
        val startHour = SecureStorage.getInt(appContext, SecureStorage.KEY_WORK_WINDOW_START_HOUR, 20)
        val startMinute = SecureStorage.getInt(appContext, SecureStorage.KEY_WORK_WINDOW_START_MINUTE, 0)
        val cycleDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val currentCycleId = "cycle_${cycleDateStr}_${startHour}_${startMinute}"
        SecureStorage.putString(appContext, SecureStorage.KEY_WORK_WINDOW_CYCLE_ID, currentCycleId)

        val workPackages = getAppsToUnlock(appContext)

        // Re-suspend designated work apps (do NOT hide to prevent OEM desktop icon deletion!)
        PolicyEnforcer.suspendPackages(appContext, workPackages.toTypedArray())

        // Re-suppress notifications
        PolicyEnforcer.suppressNotifications(appContext, workPackages.toList())

        // CRITICAL: Re-enforce web filtering!
        PolicyEnforcer.enforceWebFiltering(appContext)

        val notifMessage = if (isManualEarlyDone) {
            "Video upload done! Creator apps re-locked immediately. Good job staying focused!"
        } else {
            "Upload window ended. Focus Lock re-enforced automatically."
        }

        showNotification(
            appContext,
            "Upload Window Closed 🔒",
            notifMessage
        )

        // Schedule the next periodic window
        scheduleNextWindow(appContext)
    }

    /**
     * Validates current time against window intervals (called on wake / boot / user present).
     */
    fun checkAndSyncWindow(context: Context) {
        if (!isEnabled(context) || !SecureStorage.isLockActive(context)) return

        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val schedule = calculateWindowSchedule(appContext, now)
        val isCurrentlyActive = isWindowActive(appContext)

        if (schedule.isCurrentlyActive) {
            if (!isCurrentlyActive) {
                Log.i(TAG, "checkAndSyncWindow: Inside active window. Starting window.")
                onWindowStart(appContext)
            }
        } else {
            if (isCurrentlyActive) {
                Log.i(TAG, "checkAndSyncWindow: Window ended. Closing window.")
                onWindowEnd(appContext)
            } else {
                scheduleNextWindow(appContext)
            }
        }
    }

    fun getAppsToUnlock(context: Context): Set<String> {
        val configured = SecureStorage.getPackageSet(context, SecureStorage.KEY_WORK_WINDOW_PACKAGES)
        if (configured.isNotEmpty()) return configured

        // Default creator / work app package set if none explicitly configured:
        val blocked = SecureStorage.getBlockedPackages(context)
        val defaultUploadKeywords = listOf(
            "youtube", "tiktok", "instagram", "facebook", "drive", "chrome",
            "phoenix", "premiere", "capcut", "vlogger", "inshot", "kinemaster",
            "video", "creator", "snaptube", "camera", "iriun", "webcam", "droidcam"
        )
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

            val showIntent = Intent(context, com.focuskiosk.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val showPendingIntent = PendingIntent.getActivity(
                context,
                requestCode + 100,
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(triggerMs, showPendingIntent),
                        pendingIntent
                    )
                    Log.d(TAG, "Hardware RTC AlarmClock set for $action at $triggerMs")
                } catch (e: Exception) {
                    Log.w(TAG, "setAlarmClock failed (${e.message}), fallback to setExactAndAllowWhileIdle")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
                    } else {
                        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            }
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
