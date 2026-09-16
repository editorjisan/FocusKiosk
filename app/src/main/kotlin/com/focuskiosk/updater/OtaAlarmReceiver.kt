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
        private const val INTERVAL_MS = 20 * 60 * 1000L // 20 minutes (battery and CPU friendly)

        fun schedule(context: Context) {
            runCatching {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, OtaAlarmReceiver::class.java).apply {
                    action = ACTION_CHECK_OTA
                    setPackage(context.packageName)
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    2001,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = System.currentTimeMillis() + INTERVAL_MS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC, triggerAt, pi)
                } else {
                    am.set(AlarmManager.RTC, triggerAt, pi)
                }
                Log.d(TAG, "Scheduled next background OTA check in 20 minutes.")
            }.onFailure { Log.w(TAG, "Failed to schedule OtaAlarm: ${it.message}") }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 1. Fail-safe: check if lock timer expired
        com.focuskiosk.policy.KioskRestoreManager.checkAndRestoreIfExpired(context)

        // 2. Trigger background update check only if network is available
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val hasNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(net)
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            cm?.activeNetworkInfo?.isConnected == true
        }

        if (hasNetwork) {
            SilentUpdateManager.triggerImmediateCheck(context)
        }

        // 3. Reschedule next check
        schedule(context)
    }
}
