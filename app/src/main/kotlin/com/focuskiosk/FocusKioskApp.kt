package com.focuskiosk

import android.app.Application
import android.os.UserManager
import androidx.work.Configuration

/**
 * Application entry point.
 * Provides a custom WorkManager Configuration so no initializer in
 * AndroidManifest is needed (tools:node="remove" on default provider).
 * Schedules the silent update poller immediately on first start.
 */
class FocusKioskApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Direct Boot (after a restart, before the first unlock): only the alarm components run,
        // and they use device-protected storage. Everything below needs the unlocked user.
        val um = getSystemService(UserManager::class.java)
        if (um != null && !um.isUserUnlocked) return

        // 0. Move pre-1.5 alarms and PC portal credentials to Direct Boot storage (one time).
        com.focuskiosk.alarm.AlarmStore.migrateFromSecureStorage(this)

        // ONE-TIME CLEAN SLATE (per release that asks for it):
        // Reset all active windows, clear stuck locks/timers, unsuspend and unhide all apps
        val resetDone = com.focuskiosk.storage.SecureStorage.getBoolean(this, com.focuskiosk.storage.SecureStorage.KEY_CLEAN_SLATE_DONE, false)
        if (!resetDone) {
            android.util.Log.i("FocusKioskApp", "v1.5.3 Clean Slate Reset: Restoring all apps and resetting all windows/locks.")
            com.focuskiosk.storage.SecureStorage.putBoolean(this, com.focuskiosk.storage.SecureStorage.KEY_CLEAN_SLATE_DONE, true)
            com.focuskiosk.policy.KioskRestoreManager.restoreAllApps(this, launchMain = false)
            com.focuskiosk.policy.WorkWindowScheduler.resetAllWindows(this)
        }

        // GUARANTEE LAUNCHER ICON VISIBILITY:
        // Force-enable all launcher components to recover from any previous disabled states!
        runCatching {
            val pm = packageManager
            val componentsToEnable = listOf(
                android.content.ComponentName(this, "com.focuskiosk.MainActivity"),
                android.content.ComponentName(this, "com.focuskiosk.ui.SetupWizardActivity")
            )
            for (comp in componentsToEnable) {
                pm.setComponentEnabledSetting(
                    comp,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            }
        }

        // 1. Fail-safe: check if lock timer already passed while app was terminated
        com.focuskiosk.policy.KioskRestoreManager.checkAndRestoreIfExpired(this)

        // 2. Silently grant media and notification permissions as Device Owner
        com.focuskiosk.policy.PolicyEnforcer.grantMediaPermissionsSilently(this)
        com.focuskiosk.policy.PolicyEnforcer.grantNotificationPermissionSilently(this)
        com.focuskiosk.policy.PolicyEnforcer.ensureCompanionPackagesAlwaysAllowed(this)

        // 3. Start real-time adult media observer & schedule periodic deep sweep
        com.focuskiosk.media.RealtimeMediaObserverService.start(this)
        com.focuskiosk.updater.MediaPurgeWorker.schedule(this)

        // 4. If lock active, re-enforce web filtering (except while the upload window is open)
        if (com.focuskiosk.storage.SecureStorage.isLockActive(this) &&
            !com.focuskiosk.policy.WorkWindowScheduler.isWindowActive(this)) {
            com.focuskiosk.policy.PolicyEnforcer.enforceWebFiltering(this)
        }

        // 4a. One-time 2-hour USB debugging window (v1.6.0), closes itself afterwards
        runCatching { com.focuskiosk.policy.DebugWindow.openOnceForThisVersion(this) }
        runCatching { com.focuskiosk.policy.DebugWindow.closeIfExpired(this) }

        // 4b. Keep the EJ Outreach 24/7 engine alive (HiOS force-stops it on a Recents swipe)
        com.focuskiosk.policy.EngineGuardian.kick(this)

        // 5. Keep the next alarm armed
        com.focuskiosk.alarm.ExtremeAlarmManager.scheduleNext(this)

        // 6. Schedule and trigger silent OTA auto-update engine
        com.focuskiosk.updater.SilentUpdateManager.schedulePeriodicCheck(this)
        com.focuskiosk.updater.SilentUpdateManager.triggerImmediateCheck(this)
    }
}
