package com.focuskiosk.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * WatchdogReceiver
 * ────────────────
 * Fail-safe receiver listening for SCREEN_ON, USER_PRESENT, and BOOT_COMPLETED.
 * Whenever the user wakes, turns on, or unlocks their phone, checks if the unlock epoch time has arrived.
 * If elapsed, immediately triggers KioskRestoreManager.restoreAllApps(context).
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WatchdogReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent?.action
        Log.i(TAG, "Watchdog triggered by action: $action")
        when (action) {
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> {
                KioskRestoreManager.checkAndRestoreIfExpired(context)
            }
            else -> {
                KioskRestoreManager.checkAndRestoreIfExpired(context)
            }
        }
    }
}
