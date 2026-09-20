package com.focuskiosk.service

import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.focuskiosk.storage.SecureStorage

/**
 * FocusNotificationService
 * ────────────────────────
 * Real-time notification interceptor and sentinel.
 * Automatically suppresses and purges notifications from apps blocked during Focus Mode.
 *
 * Capabilities:
 * • onNotificationPosted: Immediately intercepts and cancels notifications from blocked apps
 *   before they are seen or interacted with by the user.
 * • dismissBlockedNotifications: Sweeps existing notifications in the shade upon Focus activation
 *   and purges any notifications belonging to blocked packages (e.g. X/Twitter, Messenger, etc.).
 * • Restores full notification flow automatically when Focus Mode is deactivated.
 */
class FocusNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "FocusNotification"

        @Volatile
        var instance: FocusNotificationService? = null
            private set

        fun clearBlockedNotifications(context: Context) {
            instance?.dismissBlockedNotifications()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "FocusNotificationService connected to notification listener subsystem.")
        dismissBlockedNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) {
            instance = null
        }
        Log.i(TAG, "FocusNotificationService disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        // If Focus Mode is active, check if this package is blocked
        if (SecureStorage.getBoolean(applicationContext, SecureStorage.KEY_LOCK_ACTIVE, false)) {
            val blocked = SecureStorage.getBlockedPackages(applicationContext)
            if (pkg in blocked) {
                Log.w(TAG, "REAL-TIME SUPPRESSION: Intercepted and cancelled notification for blocked package '$pkg'")
                cancelNotification(sbn.key)
            }
        }
    }

    /**
     * Dismisses all currently active notifications for any package that is blocked.
     */
    fun dismissBlockedNotifications() {
        if (!SecureStorage.getBoolean(applicationContext, SecureStorage.KEY_LOCK_ACTIVE, false)) return
        val blocked = SecureStorage.getBlockedPackages(applicationContext)
        if (blocked.isEmpty()) return

        runCatching {
            val active = activeNotifications ?: return
            var purged = 0
            for (sbn in active) {
                val pkg = sbn.packageName ?: continue
                if (pkg in blocked) {
                    cancelNotification(sbn.key)
                    purged++
                }
            }
            if (purged > 0) {
                Log.i(TAG, "Purged $purged existing notifications for blocked apps from shade.")
            }
        }.onFailure { Log.w(TAG, "Failed purging active notifications: ${it.message}") }
    }
}
