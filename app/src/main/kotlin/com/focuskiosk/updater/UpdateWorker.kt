package com.focuskiosk.updater

import android.content.Context
import android.util.Log
import androidx.work.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * UpdateWorker
 * ------------
 * Periodic WorkManager worker that checks for and applies app updates.
 *
 * Runs every 6 hours when the device has network connectivity.
 * Steps:
 *  1. Fetch the remote JSON manifest over HTTPS.
 *  2. Compare remote versionCode with the installed versionCode.
 *  3. If newer: download APK, verify SHA-256, call SilentInstaller.
 *
 * Replace MANIFEST_URL with your actual update endpoint URL.
 * Serve the manifest and APK from HTTPS with a valid TLS certificate.
 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG              = "UpdateWorker"
        private const val WORK_NAME        = "FocusKiosk_UpdateCheck"
        private const val MANIFEST_URL     = "https://raw.githubusercontent.com/jisan/FocusKiosk/main/update_manifest.json"
        private const val INTERVAL_HOURS   = 6L

        /**
         * Enqueues a unique periodic work request.
         * KEEP policy: repeated calls from Application.onCreate() are no-ops
         * if a request with the same name already exists.
         */
        fun schedulePeriodicCheck(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<UpdateWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            Log.i(TAG, "Update check scheduled every ${INTERVAL_HOURS}h.")
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        Log.i(TAG, "Running update check from $MANIFEST_URL...")
        return runCatching {
            // Step 1: fetch manifest.
            val manifest = fetchManifest() ?: return Result.retry()

            // Step 2: compare versions.
            val installed = applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0)
                .longVersionCode.toInt()

            Log.i(TAG, "Installed: $installed | Remote: ${manifest.versionCode}")
            if (manifest.versionCode <= installed) return Result.success()

            Log.i(TAG, "Update available — downloading ${manifest.versionName} (${manifest.apkUrl})...")

            // Step 3: download.
            val apk = downloadApk(manifest) ?: return Result.retry()

            // Step 4: verify integrity if sha256 checksum is provided.
            if (manifest.sha256.isNotBlank() && !ApkVerifier.verify(apk, manifest.sha256)) {
                Log.e(TAG, "SHA-256 verification failed for downloaded APK.")
                apk.delete()
                return Result.failure()
            }

            // Step 5: silent install using PackageInstaller + Device Owner privileges.
            Log.i(TAG, "Triggering silent PackageInstaller session...")
            SilentInstaller.installApk(applicationContext, apk)
            Result.success()

        }.getOrElse {
            Log.e(TAG, "Update check error: ${it.message}")
            Result.retry()
        }
    }

    private fun fetchManifest(): UpdateManifest? {
        return runCatching {
            val resp = http.newCall(Request.Builder().url(MANIFEST_URL).build()).execute()
            if (!resp.isSuccessful) { Log.e(TAG, "Manifest HTTP ${resp.code}"); return null }
            val j = JSONObject(resp.body?.string() ?: return null)
            UpdateManifest(
                versionCode   = j.getInt("versionCode"),
                versionName   = j.getString("versionName"),
                apkUrl        = j.getString("apkUrl"),
                sha256        = j.optString("sha256", ""),
                releaseNotes  = j.optString("releaseNotes", ""),
                minSdkVersion = j.optInt("minSdkVersion", 28)
            )
        }.getOrElse { Log.e(TAG, "Manifest parse error: ${it.message}"); null }
    }

    private fun downloadApk(manifest: UpdateManifest): File? {
        return runCatching {
            val dir  = applicationContext.externalCacheDir ?: applicationContext.cacheDir
            val file = File(dir, "update_${manifest.versionCode}.apk")
            if (file.exists() && file.length() > 0) return file  // Resume-safe.

            val resp = http.newCall(Request.Builder().url(manifest.apkUrl).build()).execute()
            if (!resp.isSuccessful) { Log.e(TAG, "APK HTTP ${resp.code}"); return null }

            resp.body?.byteStream()?.use { input ->
                file.outputStream().use { input.copyTo(it, 65536) }
            }
            Log.i(TAG, "Downloaded ${file.length()} bytes → ${file.absolutePath}")
            file
        }.getOrElse { Log.e(TAG, "Download error: ${it.message}"); null }
    }
}
