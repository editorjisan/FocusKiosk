package com.focuskiosk.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
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
import com.focuskiosk.R

/**
 * ExtremeAlarmService
 * ───────────────────
 * Foreground service that keeps the alarm going until the PC code is entered:
 *  • looping alarm sound on the alarm stream, pinned at 100% (volume changes are undone instantly)
 *  • continuous vibration, Do Not Disturb lifted while ringing
 *  • turns the screen back on and re-opens the alarm screen if the power button is pressed
 *  • restarts itself (START_STICKY + AlarmManager safety net) if it is killed
 */
class ExtremeAlarmService : Service() {

    companion object {
        private const val TAG = "ExtremeAlarmService"
        const val CHANNEL_ID = "extreme_alarm_channel_v2"
        const val NOTIFICATION_ID = 5001
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"

        /** Safety valve against a stuck phone (e.g. a forgotten portal password): stop after an hour. */
        const val MAX_RING_MS = 60 * 60 * 1000L

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ExtremeAlarmService::class.java))
            }.onFailure { Log.e(TAG, "Error starting ExtremeAlarmService: ${it.message}", it) }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ExtremeAlarmService::class.java))
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            }
        }

        fun launchAlarmScreen(context: Context) {
            if (ExtremeAlarmActivity.isForeground) return
            runCatching {
                context.startActivity(Intent(context, ExtremeAlarmActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_USER_ACTION
                ))
            }.onFailure { Log.w(TAG, "Could not open alarm screen: ${it.message}") }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var receiversRegistered = false
    private var previousInterruptionFilter: Int? = null
    private var stoppingNormally = false

    private val volumeGuard = object : Runnable {
        override fun run() {
            val since = AlarmStore.ringingSince(this@ExtremeAlarmService)
            if (since > 0L && System.currentTimeMillis() - since > MAX_RING_MS) {
                Log.w(TAG, "Alarm rang for an hour — stopping (safety cap).")
                AlarmGuard.release(null, this@ExtremeAlarmService)
                ExtremeAlarmManager.onDismissed(this@ExtremeAlarmService)
                return
            }
            pinAlarmVolume()
            handler.postDelayed(this, 600L)
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handler.postDelayed({ wakeScreen() }, 350L)
                VOLUME_CHANGED -> pinAlarmVolume()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FocusKiosk:ExtremeAlarm").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.e(TAG, "startForeground error: ${it.message}") }

        if (!AlarmStore.isRinging(this)) {
            stoppingNormally = true
            stopSelf()
            return START_NOT_STICKY
        }

        AlarmGuard.engage(this)
        liftDoNotDisturb()
        startAlarmAudio()
        startVibration()
        registerSystemReceivers()
        handler.removeCallbacks(volumeGuard)
        handler.post(volumeGuard)
        launchAlarmScreen(this)
        return START_STICKY
    }

    private fun pinAlarmVolume() {
        runCatching {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audio.isStreamMute(AudioManager.STREAM_ALARM)) {
                audio.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0)
            }
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) < max) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
        }
    }

    private fun liftDoNotDisturb() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                if (previousInterruptionFilter == null) previousInterruptionFilter = nm.currentInterruptionFilter
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        }
    }

    private fun restoreDoNotDisturb() {
        val previous = previousInterruptionFilter ?: return
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).setInterruptionFilter(previous)
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        if (!AlarmStore.isRinging(this)) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "FocusKiosk:AlarmScreen"
            ).acquire(5_000L)
        }
        launchAlarmScreen(this)
    }

    private fun registerSystemReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(VOLUME_CHANGED)
        }
        ContextCompat.registerReceiver(this, systemReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiversRegistered = true
    }

    private fun startAlarmAudio() {
        if (mediaPlayer?.isPlaying == true) return
        runCatching {
            pinAlarmVolume()
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure {
            Log.e(TAG, "Alarm tone failed, falling back to ringtone: ${it.message}")
            runCatching {
                RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    play()
                }
            }
        }
    }

    private fun startVibration() {
        if (vibrator != null) return
        runCatching {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 400, 900, 400, 1200), 0), attrs)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 5002,
            Intent(this, ExtremeAlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Alarm")
            .setContentText("Enter the code from your PC to stop it")
            .setSmallIcon(R.drawable.ic_sym_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Wake-up alarms that stop only with your PC code"
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (AlarmStore.isRinging(this)) ExtremeAlarmManager.scheduleResume(this, 1_000L)
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        if (receiversRegistered) runCatching { unregisterReceiver(systemReceiver) }
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        restoreDoNotDisturb()
        runCatching { if (cpuLock?.isHeld == true) cpuLock?.release() }
        if (!stoppingNormally && AlarmStore.isRinging(this)) {
            Log.w(TAG, "Destroyed while still ringing — scheduling resume.")
            ExtremeAlarmManager.scheduleResume(this)
        }
        super.onDestroy()
    }
}
