package com.focuskiosk.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * InstallResultReceiver
 * ---------------------
 * Receives the async result of a PackageInstaller session commit.
 * The system calls this BroadcastReceiver after the silent install
 * completes (success or failure).
 *
 * As Device Owner, STATUS_PENDING_USER_ACTION should never fire — if it does,
 * the app is not properly set as Device Owner.
 */
class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_RESULT = "com.focuskiosk.updater.INSTALL_RESULT"
        private const val TAG = "InstallResult"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status    = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message   = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Silent install SUCCESS (session $sessionId). Cleaning up...")
                // Delete downloaded APK file.
                context.externalCacheDir?.listFiles { f -> f.name.endsWith(".apk") }
                    ?.forEach { it.delete() }
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Should never happen for a Device Owner app.
                Log.w(TAG, "Install requires user action — is app Device Owner? Session: $sessionId")
            }
            else -> {
                Log.e(TAG, "Install FAILED. Status=$status, Message=$message, Session=$sessionId")
            }
        }
    }
}
