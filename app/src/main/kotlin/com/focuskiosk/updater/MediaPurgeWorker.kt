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
        Log.i(TAG, "Executing periodic adult media sweep...")
        var purgedCount = 0

        // 1. Scan filesystem directories directly
        val scanDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            File(Environment.getExternalStorageDirectory(), "Download")
        ).distinct()

        for (dir in scanDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.walkTopDown().maxDepth(3).forEach { file ->
                    if (file.isFile && AdultMediaDetector.isMediaFile(file)) {
                        if (AdultMediaDetector.isExplicit(file)) {
                            Log.w(TAG, "Sweeper found explicit file: ${file.name}")
                            if (AdultMediaDetector.purgeFile(applicationContext, file)) {
                                purgedCount++
                            }
                        }
                    }
                }
            }
        }

        // 2. Scan MediaStore records
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).forEach { uri ->
            runCatching {
                applicationContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(dataIdx) ?: continue
                        val file = File(path)
                        if (file.exists() && AdultMediaDetector.isExplicit(file)) {
                            Log.w(TAG, "MediaStore sweep found explicit file: $path")
                            if (AdultMediaDetector.purgeFile(applicationContext, file)) {
                                purgedCount++
                            }
                        }
                    }
                }
            }
        }

        Log.i(TAG, "Periodic adult media sweep completed. Purged $purgedCount file(s).")
        Result.success()
    }
}
