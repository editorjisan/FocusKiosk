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
        // WorkManager deduplicates this call via KEEP policy — safe to call every launch.
        UpdateWorker.schedulePeriodicCheck(this)
    }
}
