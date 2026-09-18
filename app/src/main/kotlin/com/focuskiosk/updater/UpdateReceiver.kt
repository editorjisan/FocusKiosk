package com.focuskiosk.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
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

            val isLockActive = com.focuskiosk.storage.SecureStorage.isLockActive(context)
            val unlockTs = com.focuskiosk.storage.SecureStorage.getUnlockTimestampMs(context)
            val now = System.currentTimeMillis()

            if (!isLockActive || now >= unlockTs) {
                Log.i(TAG, "Post-upgrade: lock is inactive or expired ($isLockActive, $now >= $unlockTs). Restoring all apps immediately!")
                com.focuskiosk.policy.KioskRestoreManager.restoreAllApps(context)
                // Relaunch the app UI so the user sees the updated version immediately
                runCatching {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    if (launchIntent != null) context.startActivity(launchIntent)
                }
            } else {
                Log.i(TAG, "Post-upgrade: lock is still active. Resuming countdown service and fail-safes.")
                com.focuskiosk.service.FocusCountdownService.start(context, unlockTs)
                com.focuskiosk.policy.KioskRestoreManager.scheduleFailSafe(context, unlockTs)
                // Relaunch home launcher
                runCatching {
                    val homeIntent = Intent(context, com.focuskiosk.launcher.HomeLauncherActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(homeIntent)
                }
            }
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
                // Attempt to launch the updated app
                runCatching {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    if (launchIntent != null) context.startActivity(launchIntent)
                }
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.w(TAG, "Install requires user action. Launching confirmation activity. Session: $sessionId")
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
            else -> {
                val errorMsg = message ?: "Status $status"
                Log.e(TAG, "Silent install failed! Status: $status, Message: $errorMsg, Session: $sessionId")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        context,
                        "Installation failed: $errorMsg",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
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
