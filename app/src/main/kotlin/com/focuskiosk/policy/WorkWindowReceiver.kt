package com.focuskiosk.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log

/**
 * WorkWindowReceiver
 * ──────────────────
 * Receives the exact alarms that open and close the upload window. Work runs off the
 * main thread via goAsync() because re-locking touches every installed package.
 */
class WorkWindowReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WorkWindowReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager
        if (!um.isUserUnlocked) return
        Log.i(TAG, "Received work window action: $action")
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                when (action) {
                    WorkWindowScheduler.ACTION_WORK_WINDOW_START -> WorkWindowScheduler.onWindowStart(appContext)
                    WorkWindowScheduler.ACTION_WORK_WINDOW_END -> WorkWindowScheduler.onWindowEnd(appContext)
                    else -> WorkWindowScheduler.checkAndSyncWindow(appContext)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Work window action failed: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
