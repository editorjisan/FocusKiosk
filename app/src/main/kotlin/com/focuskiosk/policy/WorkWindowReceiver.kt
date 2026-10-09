package com.focuskiosk.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * WorkWindowReceiver
 * ──────────────────
 * Broadcast receiver listening for exact alarms from AlarmManager
 * when a scheduled video upload/work window opens or closes.
 */
class WorkWindowReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WorkWindowReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Received work window action: $action")

        when (action) {
            WorkWindowScheduler.ACTION_WORK_WINDOW_START -> {
                WorkWindowScheduler.onWindowStart(context)
            }
            WorkWindowScheduler.ACTION_WORK_WINDOW_END -> {
                WorkWindowScheduler.onWindowEnd(context)
            }
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_SCREEN_ON -> {
                WorkWindowScheduler.checkAndSyncWindow(context)
            }
        }
    }
}
