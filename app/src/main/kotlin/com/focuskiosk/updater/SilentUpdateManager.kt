package com.focuskiosk.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * SilentUpdateManager
 * ───────────────────
 * Enterprise 100% Zero-Touch OTA Auto-Update Engine for Device Owner devices.
 *
 * Designed specifically for non-GMS / Google-less environments:
 * 1. Remote Version Check:
 *    - Polled periodically via WorkManager CoroutineWorker.
 *    - Fetches JSON manifest containing {"versionCode": X, "versionName": "...", "apkUrl": "..."}.
 * 2. Background Download & Silent PackageInstaller Session:
 *    - Streams APK into internal cacheDir.
 *    - Creates PackageInstaller.SessionParams(MODE_FULL_INSTALL).
 *    - Sets INSTALL_REASON_POLICY.
 *    - Streams byte blocks to session.openWrite() and calls fsync().
 *    - Commits session with an IntentSender directed to UpdateReceiver.
 * 3. 100% Zero-Touch Execution:
 *    - Because FocusKiosk is an active Device Owner, Android's PackageInstaller
 *      silently installs and replaces the application without user confirmation.
 */
object SilentUpdateManager {

    private const val TAG = "SilentUpdateManager"
    private const val UPDATE_WORK_NAME = "FocusKiosk_SilentUpdateCheck"
    private const val DEFAULT_INTERVAL_HOURS = 4L

    // Configurable endpoint (GitHub release manifest, raw JSON gist, or custom server)
    var manifestUrl: String = "https://raw.githubusercontent.com/editorjisan/FocusKiosk/main/update_manifest.json"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Enqueues periodic WorkManager job to check for updates every [intervalHours].
     */
    fun schedulePeriodicCheck(context: Context, intervalHours: Long = DEFAULT_INTERVAL_HOURS) {
        val appContext = context.applicationContext

        // 1. Hardware-backed AlarmManager recurring check every 30 minutes
        OtaAlarmReceiver.schedule(appContext)

        // 2. Periodic WorkManager backup
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<SilentUpdateWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            UPDATE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Log.i(TAG, "Silent OTA update worker and hardware alarm scheduled.")
    }

    /**
     * Performs a one-off immediate check and update (e.g. on app startup or manual trigger).
     */
    fun triggerImmediateCheck(context: Context) {
        val appContext = context.applicationContext

        // 1. Direct coroutine trigger - runs immediately on background thread without waiting on WorkManager
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "Executing immediate background OTA check directly...")
                checkAndInstallUpdate(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Direct OTA update check failed", e)
            }
        }

        // 2. Also enqueue WorkManager job as backup
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SilentUpdateWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(appContext).enqueue(request)
        Log.i(TAG, "Enqueued WorkManager immediate update check.")
    }

    /**
     * Downloads APK and executes silent PackageInstaller commit.
     * Must be called from a background thread / coroutine.
     */
    suspend fun checkAndInstallUpdate(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        try {
            Log.i(TAG, "Checking manifest at: $manifestUrl")
            val manifest = fetchManifest(manifestUrl) ?: return@withContext false

            val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionCode
            }

            Log.i(TAG, "Installed VersionCode: $currentVersionCode | Remote VersionCode: ${manifest.versionCode}")

            if (manifest.versionCode <= currentVersionCode) {
                Log.i(TAG, "App is up to date (remote ${manifest.versionCode} <= current $currentVersionCode).")
                return@withContext true
            }

            Log.i(TAG, "New version available (${manifest.versionName}). Downloading APK from: ${manifest.apkUrl}")
            val apkFile = downloadApk(appContext, manifest.apkUrl, manifest.versionCode)
                ?: return@withContext false

            if (manifest.sha256.isNotBlank() && !ApkVerifier.verify(apkFile, manifest.sha256)) {
                Log.e(TAG, "SHA-256 integrity verification failed for downloaded APK.")
                apkFile.delete()
                return@withContext false
            }

            Log.i(TAG, "Installing APK silently via Device Owner PackageInstaller...")
            performSilentInstall(appContext, apkFile)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Silent update error", e)
            false
        }
    }

    private fun fetchManifest(url: String): UpdateManifest? {
        return runCatching {
            val bustUrl = if (url.contains("?")) "$url&nocache=${System.currentTimeMillis()}" else "$url?nocache=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(bustUrl)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch manifest. HTTP ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            UpdateManifest(
                versionCode   = json.getInt("versionCode"),
                versionName   = json.optString("versionName", "1.0.0"),
                apkUrl        = json.getString("apkUrl"),
                sha256        = json.optString("sha256", ""),
                releaseNotes  = json.optString("releaseNotes", ""),
                minSdkVersion = json.optInt("minSdkVersion", 28)
            )
        }.getOrElse {
            Log.e(TAG, "Error parsing manifest: ${it.message}")
            null
        }
    }

    suspend fun checkAndInstallUpdateWithProgress(
        context: Context,
        onProgress: (percent: Int, status: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        try {
            withContext(Dispatchers.Main) { onProgress(0, "Checking GitHub manifest...") }
            Log.i(TAG, "Checking manifest at: $manifestUrl")
            val manifest = fetchManifest(manifestUrl)
            if (manifest == null) {
                withContext(Dispatchers.Main) { onProgress(0, "Failed to fetch manifest from GitHub.") }
                return@withContext false
            }

            val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionCode
            }

            Log.i(TAG, "Current VersionCode: $currentVersionCode | Remote VersionCode: ${manifest.versionCode}")

            if (manifest.versionCode <= currentVersionCode) {
                withContext(Dispatchers.Main) {
                    onProgress(100, "Already up to date (v${manifest.versionName}, Build ${manifest.versionCode}).")
                }
                return@withContext true
            }

            withContext(Dispatchers.Main) {
                onProgress(5, "New version v${manifest.versionName} found! Downloading APK...")
            }

            val apkFile = downloadApkWithProgress(appContext, manifest.apkUrl, manifest.versionCode) { pct ->
                CoroutineScope(Dispatchers.Main).launch {
                    onProgress(pct, "Downloading APK ($pct%)...")
                }
            }

            if (apkFile == null) {
                withContext(Dispatchers.Main) { onProgress(0, "APK download failed!") }
                return@withContext false
            }

            if (manifest.sha256.isNotBlank() && !ApkVerifier.verify(apkFile, manifest.sha256)) {
                withContext(Dispatchers.Main) { onProgress(0, "SHA-256 integrity verification failed!") }
                apkFile.delete()
                return@withContext false
            }

            withContext(Dispatchers.Main) {
                onProgress(95, "Installing silently via Device Owner...")
            }

            performSilentInstall(appContext, apkFile)

            withContext(Dispatchers.Main) {
                onProgress(100, "Installation session committed! App will restart shortly.")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking update with progress", e)
            withContext(Dispatchers.Main) {
                onProgress(0, "Update error: ${e.message}")
            }
            false
        }
    }

    private fun downloadApk(context: Context, apkUrl: String, versionCode: Int): File? {
        return downloadApkWithProgress(context, apkUrl, versionCode) {}
    }

    private fun downloadApkWithProgress(
        context: Context,
        apkUrl: String,
        versionCode: Int,
        onProgress: (Int) -> Unit
    ): File? {
        return runCatching {
            val targetDir = context.cacheDir
            val targetFile = File(targetDir, "FocusKiosk_v${versionCode}.apk")
            if (targetFile.exists()) targetFile.delete()

            val request = Request.Builder().url(apkUrl).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "APK download HTTP failure: ${response.code}")
                return null
            }

            val body = response.body ?: return null
            val contentLength = body.contentLength()
            var totalBytesRead = 0L

            body.byteStream().use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0) {
                            val percent = ((totalBytesRead * 100) / contentLength).toInt().coerceIn(0, 95)
                            onProgress(percent)
                        }
                    }
                    output.flush()
                }
            }
            Log.i(TAG, "Downloaded ${targetFile.length()} bytes to ${targetFile.absolutePath}")
            targetFile
        }.getOrElse {
            Log.e(TAG, "Error downloading APK: ${it.message}")
            null
        }
    }

    /**
     * Executes the PackageInstaller session flow.
     */
    fun performSilentInstall(context: Context, apkFile: File) {
        require(apkFile.exists() && apkFile.length() > 0) { "APK file is missing or empty" }

        val packageInstaller = context.packageManager.packageInstaller

        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            setSize(apkFile.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInstallReason(PackageManager.INSTALL_REASON_POLICY)
            }
            // MANDATORY FOR ZERO-TOUCH ON ANDROID 12+ (API 31+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        // 1. Ensure install restrictions are not blocking our update session
        com.focuskiosk.policy.PolicyEnforcer.unblockAppInstalls(context)

        // 2. Abandon any dangling or stalled PackageInstaller sessions created earlier
        runCatching {
            packageInstaller.mySessions.forEach { sessionInfo ->
                Log.w(TAG, "Abandoning dangling PackageInstaller session: ${sessionInfo.sessionId}")
                runCatching { packageInstaller.abandonSession(sessionInfo.sessionId) }
            }
        }.onFailure { Log.w(TAG, "Error cleaning up dangling sessions: ${it.message}") }

        val sessionId = packageInstaller.createSession(params)
        Log.i(TAG, "Created PackageInstaller session ID: $sessionId")

        packageInstaller.openSession(sessionId).use { session ->
            session.openWrite("focuskiosk_update.apk", 0, apkFile.length()).use { outStream ->
                FileInputStream(apkFile).use { inStream ->
                    inStream.copyTo(outStream, 65536)
                }
                session.fsync(outStream)
            }

            val callbackIntent = Intent(context, UpdateReceiver::class.java).apply {
                action = UpdateReceiver.ACTION_INSTALL_RESULT
                putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            session.commit(pendingIntent.intentSender)
            Log.i(TAG, "Committed silent install session for ${apkFile.name}")
        }
    }

    /**
     * WorkManager worker that executes silent updates.
     */
    class SilentUpdateWorker(
        context: Context,
        params: WorkerParameters
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            Log.i(TAG, "SilentUpdateWorker triggered...")
            val success = checkAndInstallUpdate(applicationContext)
            return if (success) Result.success() else Result.retry()
        }
    }
}
