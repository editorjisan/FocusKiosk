package com.focuskiosk.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.focuskiosk.R
import com.focuskiosk.policy.KioskRestoreManager
import com.focuskiosk.storage.SecureStorage
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * FocusCountdownService
 * ─────────────────────
 * Lightweight sticky Foreground Service that runs during active focus lock.
 *
 * Purposes:
 *  1. Holds an ongoing, low-priority notification showing the exact time remaining.
 *  2. Prevents OEM task killers (such as Infinix XOS, Xiaomi MIUI, Samsung OneUI)
 *     from killing the app or ignoring background alarms.
 *  3. When the countdown reaches 0, directly executes [KioskRestoreManager.restoreAllApps]
 *     and stops itself.
 */
class FocusCountdownService : Service() {

    companion object {
        private const val TAG = "FocusCountdownService"
        const val CHANNEL_ID = "focus_countdown_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_UNLOCK_TIMESTAMP = "extra_unlock_timestamp"

        fun start(context: Context, unlockTimestampMs: Long) {
            val intent = Intent(context, FocusCountdownService::class.java).apply {
                putExtra(EXTRA_UNLOCK_TIMESTAMP, unlockTimestampMs)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "Started FocusCountdownService for unlock time: $unlockTimestampMs")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FocusCountdownService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, FocusCountdownService::class.java)
                context.stopService(intent)
                Log.i(TAG, "Stopped FocusCountdownService")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping FocusCountdownService: ${e.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var countdownJob: Job? = null
    private var unlockTimestampMs: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Acquire Partial WakeLock to prevent Infinix aggressive battery optimization from suspending countdown
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FocusKiosk:CountdownWakeLock").apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24-hour safeguard maximum
            }
            Log.i(TAG, "Acquired PARTIAL_WAKE_LOCK for FocusCountdownService.")
        }.onFailure { Log.w(TAG, "Failed to acquire WakeLock: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val extraTs = intent?.getLongExtra(EXTRA_UNLOCK_TIMESTAMP, 0L) ?: 0L
        if (extraTs > 0L) {
            unlockTimestampMs = extraTs
        } else {
            unlockTimestampMs = SecureStorage.getUnlockTimestampMs(this)
        }

        Log.i(TAG, "onStartCommand: unlockTimestampMs=$unlockTimestampMs")

        // Immediately start foreground with high-priority sticky notification
        val initialRemaining = (unlockTimestampMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val notification = buildNotification(formatRemainingTime(initialRemaining))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startCountdown()

        return START_STICKY
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val remainingMs = unlockTimestampMs - now

                if (remainingMs <= 0L) {
                    Log.i(TAG, "Countdown reached 0. Triggering restoration and stopping service.")
                    updateNotification("Focus lock complete. Restoring apps...")
                    // Direct restoration
                    KioskRestoreManager.restoreAllApps(applicationContext)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }

                updateNotification(formatRemainingTime(remainingMs))
                delay(1000L)
            }
        }
    }

    private fun formatRemainingTime(remainingMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(remainingMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d remaining", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d remaining", minutes, seconds)
        }
    }

    private fun updateNotification(timeText: String) {
        val notification = buildNotification(timeText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Focus Mode Active")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Focus Mode Countdown",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows sticky countdown timer while Focus Mode is active"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownJob?.cancel()
        serviceScope.cancel()
        runCatching {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "Released PARTIAL_WAKE_LOCK.")
                }
            }
        }
        Log.i(TAG, "FocusCountdownService destroyed.")
    }
}
