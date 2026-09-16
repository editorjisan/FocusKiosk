package com.focuskiosk.updater

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Neutralized legacy worker.
 * Ensures any previously scheduled media purge is immediately cancelled
 * to guarantee zero user media access, zero data leakage, and zero CPU usage.
 */
class MediaPurgeWorker(ctx: Context, params: WorkerParameters)
    : CoroutineWorker(ctx, params) {

    companion object {
        const val WORK_NAME = "FocusKiosk_MediaPurge"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        WorkManager.getInstance(applicationContext).cancelUniqueWork(WORK_NAME)
        return Result.success()
    }
}
