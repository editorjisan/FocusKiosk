package com.focuskiosk.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focuskiosk.storage.SecureStorage

/**
 * ExtremeAlarmReceiver
 * ────────────────────
 * Listens for hardware exact alarm triggers from AlarmManager.
 * Launches the unstoppable alarm audio service and fullscreen verification activity.
 */
class ExtremeAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ExtremeAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "ExtremeAlarmReceiver triggered with action: $action")

        val appContext = context.applicationContext

        when (action) {
            ExtremeAlarmManager.ACTION_ALARM_TRIGGER -> {
                Log.i(TAG, "=======================================================")
                Log.i(TAG, "EXTREME ALARM TRIGGERED! Starting sound & activity...")
                Log.i(TAG, "=======================================================")

                SecureStorage.putBoolean(appContext, SecureStorage.KEY_ALARM_RINGING, true)

                // 1. Start foreground alarm sound and vibration service
                ExtremeAlarmService.start(appContext)

                // 2. Launch fullscreen unlock activity
                val alarmIntent = Intent(appContext, ExtremeAlarmActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                             Intent.FLAG_ACTIVITY_CLEAR_TOP or
                             Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                }
                appContext.startActivity(alarmIntent)
            }
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_SCREEN_ON -> {
                ExtremeAlarmManager.checkAndSyncAlarm(appContext)
            }
        }
    }
}
