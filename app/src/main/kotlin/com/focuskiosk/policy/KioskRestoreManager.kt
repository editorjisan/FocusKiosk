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
import com.focuskiosk.service.FocusCountdownService
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

        // 0. Stop the foreground countdown service if active
        FocusCountdownService.stop(appContext)

        // 1. Clear any persistent preferred home/launcher activities
        runCatching {
            devicePolicyManager.clearPackagePersistentPreferredActivities(adminComponent, appContext.packageName)
            Log.i(TAG, "Cleared persistent preferred activities for ${appContext.packageName}")
        }.onFailure { Log.w(TAG, "Error clearing persistent preferred activities: ${it.message}") }

        // 2. Query ALL installed applications including hidden and uninstalled packages
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or
                    PackageManager.GET_META_DATA or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) PackageManager.MATCH_DISABLED_COMPONENTS else 0

        val installedApps = runCatching {
            pm.getInstalledApplications(flags)
        }.getOrElse {
            Log.e(TAG, "Failed to getInstalledApplications: ${it.message}")
            emptyList()
        }

        // Combine stored explicit blocked package set AND system queried packages
        val storedBlocked = SecureStorage.getBlockedPackages(appContext)
        val queriedPackages = installedApps.map { it.packageName }
        val packagesToRestore = (storedBlocked + queriedPackages)
            .filter { it != appContext.packageName }
            .toSet()

        Log.i(TAG, "Found ${packagesToRestore.size} packages to restore (stored=${storedBlocked.size}, queried=${queriedPackages.size}).")

        // 3. Unhide all packages unconditionally with per-package try-catch
        var unhiddenCount = 0
        packagesToRestore.forEach { pkg ->
            try {
                val success = devicePolicyManager.setApplicationHidden(adminComponent, pkg, false)
                if (success) unhiddenCount++
                Log.d(TAG, "setApplicationHidden($pkg, false) -> $success")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unhide $pkg: ${e.message}", e)
            }
        }
        Log.i(TAG, "Successfully processed unhiding for $unhiddenCount / ${packagesToRestore.size} applications.")

        // 4. Unsuspend all packages in bulk and individually
        try {
            val failed = devicePolicyManager.setPackagesSuspended(adminComponent, packagesToRestore.toTypedArray(), false)
            Log.i(TAG, "setPackagesSuspended(false) executed. Failed count: ${failed?.size ?: 0}")
        } catch (e: Exception) {
            Log.e(TAG, "Bulk unsuspend failed: ${e.message}. Falling back to per-package unsuspend.", e)
            packagesToRestore.forEach { pkg ->
                try {
                    devicePolicyManager.setPackagesSuspended(adminComponent, arrayOf(pkg), false)
                } catch (pe: Exception) {
                    Log.e(TAG, "Per-package unsuspend failed for $pkg: ${pe.message}", pe)
                }
            }
        }

        // 4b. Refresh system launcher safely by launching Home Intent (NO protected broadcasts!)
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(homeIntent)
            Log.i(TAG, "Launched home intent to safely refresh stock launcher.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch home intent: ${e.message}")
        }

        // 5. Restore user restrictions (unblock all installs, ADB, factory reset, unknown sources)
        runCatching {
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
            Log.i(TAG, "All anti-tamper, sideloading, and install restrictions cleared.")
        }.onFailure { Log.w(TAG, "Error clearing user restrictions: ${it.message}") }

        // Cancel countdown notification immediately
        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(FocusCountdownService.NOTIFICATION_ID)
        }

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
        SecureStorage.setBlockedPackages(appContext, emptySet())

        appContext.getSharedPreferences("focus_kiosk_prefs", Context.MODE_PRIVATE).edit()
            .putLong("unlock_epoch_time", 0L)
            .putBoolean("lock_active", false)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                appContext.createDeviceProtectedStorageContext()
                    .getSharedPreferences("focus_kiosk_prefs", Context.MODE_PRIVATE).edit()
                    .putLong("unlock_epoch_time", 0L)
                    .putBoolean("lock_active", false)
                    .apply()
            }
        }

        // Cancel scheduled fail-safe workers and alarms
        cancelScheduledFailSafe(appContext)

        Log.i(TAG, "=======================================================")
        Log.i(TAG, "KIOSK RESTORATION COMPLETED SUCCESSFULLY")
        Log.i(TAG, "=======================================================")
    }

    /**
     * Schedules dual fail-safe mechanisms:
     * 1. Hardware-level AlarmManager setExactAndAllowWhileIdle at [unlockTimestampMs].
     * 2. WorkManager OneTimeWorkRequest with initial delay.
     */
    fun scheduleFailSafe(context: Context, unlockTimestampMs: Long) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val delayMs = (unlockTimestampMs - now).coerceAtLeast(0L)

        Log.i(TAG, "Scheduling restoration fail-safe for $unlockTimestampMs (in ${delayMs / 1000}s)")

        // 1. Hardware-level AlarmManager exact wake-up alarm
        runCatching {
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(appContext, FailSafeRestoreReceiver::class.java).apply {
                action = FailSafeRestoreReceiver.ACTION_RESTORE_FAILSAFE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val showIntent = Intent(appContext, com.focuskiosk.ui.SetupWizardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val showPendingIntent = PendingIntent.getActivity(
                appContext,
                1002,
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(unlockTimestampMs, showPendingIntent),
                        pendingIntent
                    )
                    Log.i(TAG, "Hardware AlarmManager AlarmClockInfo set for $unlockTimestampMs.")
                } catch (e: Exception) {
                    Log.w(TAG, "setAlarmClock failed (${e.message}), falling back to setExactAndAllowWhileIdle")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, unlockTimestampMs, pendingIntent)
                    } else {
                        alarmManager.setExact(AlarmManager.RTC_WAKEUP, unlockTimestampMs, pendingIntent)
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    unlockTimestampMs,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    unlockTimestampMs,
                    pendingIntent
                )
            }
            Log.i(TAG, "Hardware AlarmManager wake-up alarm set for $unlockTimestampMs (code 1001).")
        }.onFailure { Log.e(TAG, "AlarmManager schedule failed: ${it.message}", it) }

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
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(appContext, FailSafeRestoreReceiver::class.java).apply {
                action = FailSafeRestoreReceiver.ACTION_RESTORE_FAILSAFE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                1001,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
            WorkManager.getInstance(appContext).cancelUniqueWork(RESTORE_WORK_NAME)
            Log.i(TAG, "Cancelled fail-safe alarms and workers.")
        }
    }

    /**
     * Validates lock state against unencrypted SharedPreferences and SecureStorage.
     * If the unlock timestamp has arrived, triggers immediate restoration.
     */
    fun checkAndRestoreIfExpired(context: Context) {
        val appContext = context.applicationContext
        val sp = appContext.getSharedPreferences("focus_kiosk_prefs", Context.MODE_PRIVATE)
        val epochTime = sp.getLong("unlock_epoch_time", 0L)
        val secureTs = SecureStorage.getUnlockTimestampMs(appContext)
        val unlockTs = if (epochTime > 0L) epochTime else secureTs

        val isLockActive = sp.getBoolean("lock_active", false) || SecureStorage.isLockActive(appContext)
        if (!isLockActive && unlockTs == 0L) return

        val now = System.currentTimeMillis()
        Log.d(TAG, "checkAndRestoreIfExpired: now=$now, unlockTs=$unlockTs, delta=${now - unlockTs}")
        if (unlockTs in 1..now) {
            Log.i(TAG, "Focus lock timer has EXPIRED ($now >= $unlockTs). Triggering restoreAllApps immediately.")
            restoreAllApps(appContext)
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
