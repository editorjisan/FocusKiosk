package com.focuskiosk.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * UpdateReceiver
 * ──────────────
 * Receives the asynchronous result of a PackageInstaller session commit.
 *
 * When FocusKiosk runs as Device Owner:
 *  - STATUS_SUCCESS is delivered upon completion with ZERO user interaction.
 *  - Automatically purges cached APK payloads from disk.
 *  - If STATUS_PENDING_USER_ACTION is returned, logs a diagnostic warning
 *    indicating missing Device Owner provisioning.
 */
class UpdateReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_RESULT = "com.focuskiosk.updater.INSTALL_RESULT"
        private const val TAG = "UpdateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "UpdateReceiver received action: $action")

        if (Intent.ACTION_MY_PACKAGE_REPLACED == action) {
            Log.i(TAG, "ACTION_MY_PACKAGE_REPLACED received — app has been upgraded in the background!")
            cleanupCacheApks(context)
            // Verify lock state / fail-safe post-upgrade
            com.focuskiosk.policy.KioskRestoreManager.checkAndRestoreIfExpired(context)
            return
        }

        val status    = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message   = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "=======================================================")
                Log.i(TAG, "SILENT UPDATE SUCCESSFUL: Session $sessionId committed.")
                Log.i(TAG, "=======================================================")
                cleanupCacheApks(context)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.w(TAG, "Install requires user action. Ensure app is provisioned as Device Owner. Session: $sessionId")
                // On non-DO devices, user intent would need to be launched here.
                // On active DO devices, this branch is never hit.
            }
            else -> {
                Log.e(TAG, "Silent install failed! Status: $status, Message: $message, Session: $sessionId")
            }
        }
    }

    private fun cleanupCacheApks(context: Context) {
        runCatching {
            val cacheDirs = listOfNotNull(context.externalCacheDir, context.cacheDir)
            for (dir in cacheDirs) {
                dir.listFiles { f -> f.name.endsWith(".apk") }?.forEach { apk ->
                    val deleted = apk.delete()
                    Log.d(TAG, "Cleaned up cache APK: ${apk.name} -> $deleted")
                }
            }
        }.onFailure { Log.w(TAG, "Error cleaning cache APKs: ${it.message}") }
    }
}
