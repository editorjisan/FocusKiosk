package com.focuskiosk.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ExtremeAlarmReceiver
 * ────────────────────
 * Direct Boot aware: fires alarms, re-arms them after reboot / clock changes and
 * resumes an alarm that was ringing when the phone was force-restarted.
 */
class ExtremeAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ExtremeAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "onReceive: $action")
        val appContext = context.applicationContext

        when (action) {
            ExtremeAlarmManager.ACTION_ALARM_TRIGGER ->
                ExtremeAlarmManager.onTriggered(appContext, intent.getStringExtra(ExtremeAlarmManager.EXTRA_ALARM_ID))

            ExtremeAlarmManager.ACTION_RESUME_RINGING ->
                if (AlarmStore.isRinging(appContext)) ExtremeAlarmManager.startRinging(appContext, null)

            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                ExtremeAlarmManager.checkAndSync(appContext, resumeRinging = true)

            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED ->
                ExtremeAlarmManager.checkAndSync(appContext, resumeRinging = false)
        }
    }
}
