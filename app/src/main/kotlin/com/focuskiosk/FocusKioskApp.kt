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
        // 1. Fail-safe: check if lock timer already passed while app was terminated
        com.focuskiosk.policy.KioskRestoreManager.checkAndRestoreIfExpired(this)

        // 2. Cancel any legacy media purge workers to ensure zero media access & zero background CPU drain
        runCatching {
            androidx.work.WorkManager.getInstance(this).cancelUniqueWork("FocusKiosk_MediaPurge")
        }

        // 3. Schedule and trigger silent OTA auto-update engine
        com.focuskiosk.updater.SilentUpdateManager.schedulePeriodicCheck(this)
        com.focuskiosk.updater.SilentUpdateManager.triggerImmediateCheck(this)
    }
}
