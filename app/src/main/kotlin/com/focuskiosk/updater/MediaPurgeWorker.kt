package com.focuskiosk.updater

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.*
import com.focuskiosk.media.AdultMediaDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * MediaPurgeWorker
 * ────────────────
 * Periodic background WorkManager worker executing a deep scan across all media folders.
 * Acts as a failsafe sweep catching explicit/adult images & videos that may have bypassed
 * real-time observation (e.g. transferred via SD card, USB, or offline file manager).
 */
class MediaPurgeWorker(ctx: Context, params: WorkerParameters)
    : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "MediaPurgeWorker"
        const val WORK_NAME = "FocusKiosk_MediaPurgeSweep"
        private const val INTERVAL_HOURS = 2L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<MediaPurgeWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.i(TAG, "Media purge sweep scheduled every ${INTERVAL_HOURS}h.")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Executing periodic adult media sweep via WorkManager...")
        val (scanned, purged) = com.focuskiosk.media.RealtimeMediaObserverService.performFullSweep(applicationContext)
        Log.i(TAG, "Periodic adult media sweep completed: $scanned scanned, $purged purged.")
        Result.success()
    }
}
