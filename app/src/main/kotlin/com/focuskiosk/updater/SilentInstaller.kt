package com.focuskiosk.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * SilentInstaller
 * ---------------
 * Installs an APK file silently using PackageInstaller.
 *
 * Why this works without user interaction:
 *  - When the calling app is a Device Owner, the system grants it
 *    INSTALL_PACKAGES permission implicitly.
 *  - Calling session.commit() with an IntentSender that routes to
 *    InstallResultReceiver avoids any interactive confirmation dialog.
 *
 * Session flow:
 *  1. Create session with MODE_FULL_INSTALL (full APK replacement).
 *  2. Stream APK bytes into the session via openWrite().
 *  3. Call fsync() to ensure data is flushed before commit.
 *  4. Call commit() with a PendingIntent → installation starts silently.
 *  5. InstallResultReceiver receives the success/failure callback.
 */
object SilentInstaller {

    private const val TAG = "SilentInstaller"

    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.e(TAG, "APK missing or empty: ${apkFile.absolutePath}"); return
        }

        val installer = context.packageManager.packageInstaller

        // 1. Configure session.
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            setSize(apkFile.length())
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                setInstallReason(PackageManager.INSTALL_REASON_POLICY)
            }
        }

        val sessionId = installer.createSession(params)
        Log.i(TAG, "PackageInstaller session created: $sessionId")

        // 2. Open session and write APK bytes.
        installer.openSession(sessionId).use { session ->
            session.openWrite("focuskiosk.apk", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { it.copyTo(out, 65536) }
                session.fsync(out) // Flush before committing.
            }

            // 3. Build IntentSender pointing to InstallResultReceiver.
            val intent = Intent(context, InstallResultReceiver::class.java).apply {
                action = InstallResultReceiver.ACTION_INSTALL_RESULT
                putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            }
            val pi = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 4. Commit — silently installs as Device Owner.
            session.commit(pi.intentSender)
            Log.i(TAG, "Install session committed: ${apkFile.name}")
        }
    }
}
