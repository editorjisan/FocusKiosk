package com.focuskiosk.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

/**
 * FocusCountdownService
 * ─────────────────────
 * Foreground Service running an active 1-second coroutine loop to monitor unlock time.
 * As soon as 00:00 is reached:
 *  1. Instantly unhides & unsuspends all apps via KioskRestoreManager.
 *  2. Dismisses the notification and stops the service to prevent negative chronometer counts (-00:01, -00:02).
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
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_ID)
                Log.i(TAG, "Stopped FocusCountdownService and dismissed notification.")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping FocusCountdownService: ${e.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var countdownJob: Job? = null
    private var unlockTimestampMs: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            Log.d(TAG, "screenReceiver action: ${intent?.action}")
            val context = ctx ?: return
            val now = System.currentTimeMillis()
            if (unlockTimestampMs in 1..now) {
                Log.i(TAG, "Screen on detected past expiry ($now >= $unlockTimestampMs)! Dismissing notification and restoring.")
                runCatching {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(NOTIFICATION_ID)
                }
                stopSelf()
                CoroutineScope(Dispatchers.IO).launch {
                    KioskRestoreManager.restoreAllApps(context)
                }
            } else {
                ctx.let { KioskRestoreManager.checkAndRestoreIfExpired(it) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Register dynamic screen-on and user-present receiver
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenReceiver, filter)
            Log.i(TAG, "Registered screenReceiver for SCREEN_ON, SCREEN_OFF, and USER_PRESENT.")
        }.onFailure { Log.w(TAG, "Failed to register screenReceiver: ${it.message}") }

        // Acquire Partial WakeLock to ensure the CPU stays alive for the countdown loop
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FocusKiosk:CountdownWakeLock").apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
            Log.i(TAG, "Acquired PARTIAL_WAKE_LOCK for countdown loop.")
        }.onFailure { Log.w(TAG, "Failed to acquire WakeLock: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val extraTs = intent?.getLongExtra(EXTRA_UNLOCK_TIMESTAMP, 0L) ?: 0L
        val spTs = getSharedPreferences("focus_kiosk_prefs", Context.MODE_PRIVATE).getLong("unlock_epoch_time", 0L)
        val secureTs = SecureStorage.getUnlockTimestampMs(this)
        unlockTimestampMs = when {
            extraTs > 0L -> extraTs
            spTs > 0L -> spTs
            else -> secureTs
        }

        Log.i(TAG, "onStartCommand: unlockTimestampMs=$unlockTimestampMs")

        // Check if already expired
        if (unlockTimestampMs in 1..System.currentTimeMillis()) {
            Log.i(TAG, "Timestamp already reached. Restoring apps immediately.")
            KioskRestoreManager.restoreAllApps(this)
            stopSelf()
            return START_NOT_STICKY
        }

        // Build native Chronometer notification
        val notification = buildChronometerNotification(unlockTimestampMs)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "startForeground succeeded with native chronometer (when=$unlockTimestampMs).")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
        }

        // Active 1-second coroutine loop: executes restoration the moment 00:00 is reached
        startActiveCountdownLoop()

        return START_STICKY
    }

    private fun startActiveCountdownLoop() {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val remainingMs = unlockTimestampMs - now

                if (remainingMs <= 0L) {
                    Log.i(TAG, "Timer expired at 00:00! Dismissing notification and restoring apps.")
                    // 1. Immediately dismiss notification and stop foreground so negative numbers never show
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(NOTIFICATION_ID)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error clearing notification: ${e.message}")
                    }

                    // 2. Update persistent state and stop service
                    SecureStorage.putBoolean(applicationContext, SecureStorage.KEY_LOCK_ACTIVE, false)
                    stopSelf()

                    // 3. Trigger complete restoration on background IO thread
                    CoroutineScope(Dispatchers.IO).launch {
                        KioskRestoreManager.restoreAllApps(applicationContext)
                    }
                    break
                }
                delay(1000L)
            }
        }
    }

    private fun buildChronometerNotification(unlockEpoch: Long): Notification {
        val openIntent = Intent(this, com.focuskiosk.ui.SetupWizardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Focus Lock Active")
            .setContentText("Focus mode is actively enforced")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(openPendingIntent)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(unlockEpoch)
            .setShowWhen(true)
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
                description = "Shows countdown timer while Focus Mode is active"
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
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        }
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
