package com.focuskiosk.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * FailSafeRestoreReceiver
 * ───────────────────────
 * Standalone top-level BroadcastReceiver triggered by AlarmManager at the exact unlock timestamp.
 *
 * NOTE: Must be a top-level class (NOT an inner class inside an object)
 * so Android's runtime reflection can instantiate it with Class.newInstance().
 */
class FailSafeRestoreReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FailSafeRestoreReceiver"
        const val ACTION_RESTORE_FAILSAFE = "com.focuskiosk.ACTION_RESTORE_FAILSAFE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "FailSafeRestoreReceiver received action: ${intent.action}")
        // Hardware alarm triggered: unconditionally restore all apps
        KioskRestoreManager.restoreAllApps(context)
    }
}
