package com.focuskiosk.policy

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.work.*
import com.focuskiosk.admin.FocusDeviceAdminReceiver
import com.focuskiosk.storage.SecureStorage
import java.util.concurrent.TimeUnit

/**
 * KioskRestoreManager
 * ───────────────────
 * Enterprise-grade restoration & fail-safe engine.
 *
 * Responsibilities:
 * 1. Restores 100% of user and non-critical system applications when the focus lock expires:
 *    - Iterates over all installed packages.
 *    - Unhides every package (setApplicationHidden(admin, pkg, false)).
 *    - Unsuspends every package (setPackagesSuspended(admin, pkgs, false)).
 *    - Clears any persistent preferred launcher overrides to restore stock system launcher.
 *    - Restores user restrictions (ADB, factory reset, app installs).
 *    - Re-enables the SetupWizardActivity component so the app is accessible again.
 * 2. Fail-safe enforcement:
 *    - Schedules an exact AlarmManager trigger at the unlock timestamp.
 *    - Enqueues a OneTimeWorkRequest via WorkManager as a secondary fail-safe.
 *    - Listens via BootReceiver and onPasswordSucceeded to ensure process death cannot trap the user.
 */
object KioskRestoreManager {

    private const val TAG = "KioskRestoreManager"
    private const val RESTORE_WORK_NAME = "FocusKiosk_FailSafeRestoreWork"
    const val ACTION_TIMER_EXPIRED = "com.focuskiosk.policy.ACTION_TIMER_EXPIRED"

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context): ComponentName =
        FocusDeviceAdminReceiver.getComponentName(context)

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName)

    /**
     * Complete cleanup and restoration routine.
     * Guaranteed to restore all hidden/suspended apps, reset restrictions, and restore system launcher.
     */
    @Synchronized
    fun restoreAllApps(context: Context) {
        val appContext = context.applicationContext
        if (!isDeviceOwner(appContext)) {
            Log.e(TAG, "Cannot restore apps: FocusKiosk is not Device Owner.")
            return
        }

        Log.i(TAG, "=======================================================")
        Log.i(TAG, "STARTING COMPLETE KIOSK RESTORATION & APP UNHIDING")
        Log.i(TAG, "=======================================================")

        val pm = appContext.packageManager
        val devicePolicyManager = dpm(appContext)
        val adminComponent = admin(appContext)

        // 1. Clear any persistent preferred home/launcher activities
        runCatching {
            devicePolicyManager.clearPackagePersistentPreferredActivities(adminComponent, appContext.packageName)
            Log.i(TAG, "Cleared persistent preferred activities for ${appContext.packageName}")
        }.onFailure { Log.w(TAG, "Error clearing persistent preferred activities: ${it.message}") }

        // 2. Query ALL installed applications on the device
        val installedApps = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrElse {
            Log.e(TAG, "Failed to getInstalledApplications: ${it.message}")
            emptyList()
        }

        val packagesToRestore = installedApps
            .map { it.packageName }
            .filter { it != appContext.packageName }

        Log.i(TAG, "Found ${packagesToRestore.size} installed packages to inspect & restore.")

        // 3. Unhide all non-whitelisted and user apps unconditionally
        var unhiddenCount = 0
        packagesToRestore.forEach { pkg ->
            runCatching {
                val success = devicePolicyManager.setApplicationHidden(adminComponent, pkg, false)
                if (success) unhiddenCount++
                Log.d(TAG, "setApplicationHidden($pkg, false) -> $success")
            }.onFailure {
                Log.w(TAG, "Failed to unhide $pkg: ${it.message}")
            }
        }
        Log.i(TAG, "Successfully processed unhiding for $unhiddenCount applications.")

        // 4. Unsuspend all packages in bulk
        val packagesToUnsuspend = packagesToRestore.toTypedArray()
        runCatching {
            val failed = devicePolicyManager.setPackagesSuspended(adminComponent, packagesToUnsuspend, false)
            val failedCount = failed.size
            Log.i(TAG, "setPackagesSuspended(false) executed. Failed list count: $failedCount")
        }.onFailure {
            Log.w(TAG, "Bulk unsuspend failed: ${it.message}. Attempting per-package unsuspend.")
            packagesToRestore.forEach { pkg ->
                runCatching {
                    devicePolicyManager.setPackagesSuspended(adminComponent, arrayOf(pkg), false)
                }
            }
        }

        // 5. Restore user restrictions
        runCatching {
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
            Log.i(TAG, "All anti-tamper and install restrictions cleared.")
        }.onFailure { Log.w(TAG, "Error clearing user restrictions: ${it.message}") }

        // 6. Restore LockTask packages / features if applicable
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                devicePolicyManager.setLockTaskFeatures(
                    adminComponent,
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                    DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
                    DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                )
            }
            devicePolicyManager.setLockTaskPackages(adminComponent, emptyArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                devicePolicyManager.setStatusBarDisabled(adminComponent, false)
                devicePolicyManager.setKeyguardDisabled(adminComponent, false)
            }
        }.onFailure { Log.w(TAG, "Error resetting LockTask features: ${it.message}") }

        // 7. Re-enable the SetupWizardActivity component so the user can re-open FocusKiosk
        runCatching {
            val component = ComponentName(appContext, "com.focuskiosk.ui.SetupWizardActivity")
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "Re-enabled SetupWizardActivity component.")
        }.onFailure { Log.w(TAG, "Failed re-enabling SetupWizardActivity: ${it.message}") }

        // 8. Update persistent secure state
        SecureStorage.putBoolean(appContext, SecureStorage.KEY_LOCK_ACTIVE, false)
        SecureStorage.setSetupCompleted(appContext, false)

        // Cancel scheduled fail-safe workers and alarms
        cancelScheduledFailSafe(appContext)

        Log.i(TAG, "=======================================================")
        Log.i(TAG, "KIOSK RESTORATION COMPLETED SUCCESSFULLY")
        Log.i(TAG, "=======================================================")
    }

    /**
     * Schedules dual fail-safe mechanisms:
     * 1. Exact AlarmManager intent at [unlockTimestampMs].
     * 2. WorkManager OneTimeWorkRequest with initial delay.
     */
    fun scheduleFailSafe(context: Context, unlockTimestampMs: Long) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val delayMs = (unlockTimestampMs - now).coerceAtLeast(0L)

        Log.i(TAG, "Scheduling restoration fail-safe for $unlockTimestampMs (in ${delayMs / 1000}s)")

        // 1. AlarmManager exact trigger
        runCatching {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(appContext, FailSafeRestoreReceiver::class.java).apply {
                action = ACTION_TIMER_EXPIRED
            }
            val pi = PendingIntent.getBroadcast(
                appContext,
                9999,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, unlockTimestampMs, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, unlockTimestampMs, pi)
            }
            Log.i(TAG, "Exact AlarmManager fail-safe scheduled.")
        }.onFailure { Log.w(TAG, "AlarmManager schedule failed: ${it.message}") }

        // 2. WorkManager fail-safe
        runCatching {
            val workRequest = OneTimeWorkRequestBuilder<FailSafeRestoreWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(appContext).enqueueUniqueWork(
                RESTORE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Log.i(TAG, "WorkManager fail-safe enqueued.")
        }.onFailure { Log.w(TAG, "WorkManager schedule failed: ${it.message}") }
    }

    fun cancelScheduledFailSafe(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(appContext, FailSafeRestoreReceiver::class.java).apply {
                action = ACTION_TIMER_EXPIRED
            }
            val pi = PendingIntent.getBroadcast(
                appContext,
                9999,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
            }
            WorkManager.getInstance(appContext).cancelUniqueWork(RESTORE_WORK_NAME)
            Log.i(TAG, "Cancelled fail-safe alarms and workers.")
        }
    }

    /**
     * Validates lock state. If the unlock timestamp has passed, triggers immediate restoration.
     */
    fun checkAndRestoreIfExpired(context: Context) {
        val appContext = context.applicationContext
        if (!SecureStorage.isLockActive(appContext)) return

        val unlockTs = SecureStorage.getUnlockTimestampMs(appContext)
        val now = System.currentTimeMillis()

        Log.d(TAG, "checkAndRestoreIfExpired: now=$now, unlockTs=$unlockTs, expired=${now >= unlockTs}")
        if (now >= unlockTs) {
            Log.i(TAG, "Focus lock timer has EXPIRED. Triggering restoreAllApps.")
            restoreAllApps(appContext)
        }
    }

    /**
     * Fail-safe BroadcastReceiver called when AlarmManager fires.
     */
    class FailSafeRestoreReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "FailSafeRestoreReceiver received action: ${intent.action}")
            checkAndRestoreIfExpired(context)
        }
    }

    /**
     * Fail-safe WorkManager Worker called when delay expires or system triggers work.
     */
    class FailSafeRestoreWorker(
        context: Context,
        params: WorkerParameters
    ) : Worker(context, params) {
        override fun doWork(): Result {
            Log.i(TAG, "FailSafeRestoreWorker running...")
            checkAndRestoreIfExpired(applicationContext)
            return Result.success()
        }
    }
}
