package com.focuskiosk.updater

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * OtaAlarmReceiver
 * ────────────────
 * Hardware-backed recurring AlarmManager receiver that triggers background silent OTA checks.
 * Bypasses WorkManager 4-hour deferrals and OEM task restrictions.
 */
class OtaAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OtaAlarmReceiver"
        const val ACTION_CHECK_OTA = "com.focuskiosk.ACTION_CHECK_OTA"
        private const val INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

        fun schedule(context: Context) {
            runCatching {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, OtaAlarmReceiver::class.java).apply {
                    action = ACTION_CHECK_OTA
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    2001,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = System.currentTimeMillis() + INTERVAL_MS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
                Log.i(TAG, "Scheduled next hardware OTA check in 30 minutes.")
            }.onFailure { Log.w(TAG, "Failed to schedule OtaAlarm: ${it.message}") }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "OtaAlarmReceiver triggered. Running silent OTA check...")
        SilentUpdateManager.triggerImmediateCheck(context)
        schedule(context)
    }
}
