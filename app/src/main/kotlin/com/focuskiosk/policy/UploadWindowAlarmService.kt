package com.focuskiosk.policy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UploadWindowAlarmService
 * ────────────────────────
 * Rings a normal alarm (alarm volume, looping tone, vibration) the moment the upload window
 * opens. Unlike the wake-up alarms, it needs no PC code: "Stop" on the notification, swiping
 * it away, or opening FocusKiosk silences it. It also stops by itself after a few minutes.
 */
class UploadWindowAlarmService : Service() {

    companion object {
        private const val TAG = "UploadWindowAlarm"
        private const val CHANNEL_ID = "upload_window_alarm_channel_v2"
        private const val INFO_CHANNEL_ID = "focus_work_window_channel"
        const val NOTIF_ID = 2003
        const val INFO_NOTIF_ID = 2002
        private const val ACTION_START = "com.focuskiosk.policy.UPLOAD_ALARM_START"
        private const val ACTION_STOP = "com.focuskiosk.policy.UPLOAD_ALARM_STOP"
        private const val EXTRA_END_MS = "end_ms"
        private const val AUTO_STOP_MS = 3 * 60_000L

        @Volatile var isRinging = false
            private set

        fun start(context: Context, windowEndMs: Long) {
            val intent = Intent(context, UploadWindowAlarmService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_END_MS, windowEndMs)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "Could not start upload alarm: ${it.message}") }
        }

        fun stop(context: Context) {
            if (!isRinging) return
            runCatching {
                context.startService(Intent(context, UploadWindowAlarmService::class.java).setAction(ACTION_STOP))
            }.onFailure {
                // Service already gone: just clear the ringing notification.
                isRinging = false
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
            }
        }

        private fun timeFmt(ms: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var windowEndMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { finishRinging() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishRinging()
            return START_NOT_STICKY
        }
        windowEndMs = intent?.getLongExtra(EXTRA_END_MS, 0L) ?: 0L
        createChannels()
        val notification = buildRingingNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }.onFailure {
            Log.e(TAG, "startForeground failed: ${it.message}")
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification)
        }
        if (!isRinging) {
            isRinging = true
            acquireWakeLock()
            startSound()
            startVibration()
        }
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, AUTO_STOP_MS)
        return START_NOT_STICKY
    }

    /** Silences the alarm and leaves a quiet "window open" notification behind. */
    private fun finishRinging() {
        handler.removeCallbacks(autoStop)
        stopSoundAndVibration()
        isRinging = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
        if (WorkWindowScheduler.isWindowActive(applicationContext)) {
            val end = if (windowEndMs > 0L) windowEndMs else
                com.focuskiosk.storage.SecureStorage.getLong(applicationContext, com.focuskiosk.storage.SecureStorage.KEY_NEXT_WINDOW_END_MS, 0L)
            runCatching { nm.notify(INFO_NOTIF_ID, buildInfoNotification(end)) }
        }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        stopSoundAndVibration()
        isRinging = false
        super.onDestroy()
    }

    // ── Sound & vibration ─────────────────────────────────────────────────────

    private fun startSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(this@UploadWindowAlarmService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        }.onFailure {
            Log.w(TAG, "MediaPlayer failed (${it.message}); falling back to Ringtone.")
            runCatching {
                RingtoneManager.getRingtone(this, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                    play()
                }
            }
        }
    }

    private fun startVibration() {
        runCatching {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500, 700, 1500), 0))
        }
    }

    private fun stopSoundAndVibration() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "FocusKiosk:UploadWindowAlarm"
            ).apply { acquire(AUTO_STOP_MS + 10_000L) }
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Upload Window Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings when the upload window opens"
                setSound(null, null) // the service plays the alarm tone itself
                enableVibration(false)
                enableLights(true)
                lightColor = Color.GREEN
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(INFO_CHANNEL_ID, "Upload Window", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When your phone unlocks and locks again for uploads"
            }
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 4101,
        Intent(this, com.focuskiosk.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this, 4102,
        Intent(this, UploadWindowAlarmService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildRingingNotification(): Notification {
        val until = if (windowEndMs > 0L) " until ${timeFmt(windowEndMs)}" else ""
        val text = "Your upload window is open$until. Upload your videos now — everything is unlocked."
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.focuskiosk.R.drawable.ic_sym_upload)
            .setContentTitle("Upload time!")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .setFullScreenIntent(openAppIntent(), true)
            .setDeleteIntent(stopIntent())
            .addAction(com.focuskiosk.R.drawable.ic_sym_upload, "Stop", stopIntent())
            .build()
    }

    private fun buildInfoNotification(endMs: Long): Notification {
        val text = if (endMs > 0L) "Everything is unlocked until ${timeFmt(endMs)}. It locks again automatically."
        else "Everything is unlocked. It locks again automatically."
        return NotificationCompat.Builder(this, INFO_CHANNEL_ID)
            .setSmallIcon(com.focuskiosk.R.drawable.ic_sym_upload)
            .setContentTitle("Upload Window Open")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
    }
}
