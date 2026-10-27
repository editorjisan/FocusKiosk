package com.focuskiosk

import android.app.Application
import androidx.work.Configuration
import com.focuskiosk.updater.UpdateWorker

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

        // v1.4.4 FORCE CLEAN SLATE UNLOCK:
        // Trigger immediate restore as early as Application onCreate to clear any stuck 30-day locks
        runCatching {
            val resetDone = com.focuskiosk.storage.SecureStorage.getBoolean(this, com.focuskiosk.storage.SecureStorage.KEY_RESET_APPLIED_V144, false)
            if (!resetDone) {
                com.focuskiosk.storage.SecureStorage.putBoolean(this, com.focuskiosk.storage.SecureStorage.KEY_RESET_APPLIED_V144, true)
                com.focuskiosk.policy.KioskRestoreManager.restoreAllApps(this)
            }
        }

        // 0. GUARANTEE LAUNCHER ICON VISIBILITY:
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

        // 2. Silently grant media permissions as Device Owner
        com.focuskiosk.policy.PolicyEnforcer.grantMediaPermissionsSilently(this)

        // 3. Start real-time adult media observer & schedule periodic deep sweep
        com.focuskiosk.media.RealtimeMediaObserverService.start(this)
        com.focuskiosk.updater.MediaPurgeWorker.schedule(this)

        // 4. If lock active, re-enforce web filtering and URLBlocklist
        if (com.focuskiosk.storage.SecureStorage.isLockActive(this)) {
            com.focuskiosk.policy.PolicyEnforcer.enforceWebFiltering(this)
        }

        // 5. Schedule and trigger silent OTA auto-update engine
        com.focuskiosk.updater.SilentUpdateManager.schedulePeriodicCheck(this)
        com.focuskiosk.updater.SilentUpdateManager.triggerImmediateCheck(this)
    }
}
