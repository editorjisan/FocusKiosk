package com.focuskiosk.policy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * EngineGuardian
 * ──────────────
 * HiOS/XOS force-stops an app when it is swiped away from Recents, which also wipes the app's own
 * alarms and jobs, so the EJ Outreach 24/7 engine can't bring itself back. FocusKiosk is device owner
 * (it can't be force-stopped and may start foreground services from the background), so every few
 * minutes it asks the engine's foreground service to start. If the engine is already running this is
 * a no-op; if it was killed it comes back within ~3 minutes. A user-pressed "Stop" is respected
 * (the engine checks the "fromGuardian" extra).
 */
object EngineGuardian {
    private const val TAG = "EngineGuardian"
    private const val ENGINE_PKG = PolicyEnforcer.OUTREACH_ENGINE_PACKAGE
    private const val ENGINE_SERVICE = "id.flutter.flutter_background_service.BackgroundService"
    private const val INTERVAL_MS = 3 * 60_000L
    private const val REQ = 7401
    const val ACTION_TICK = "com.focuskiosk.policy.ENGINE_GUARDIAN_TICK"

    private fun installed(context: Context) =
        runCatching { context.packageManager.getApplicationInfo(ENGINE_PKG, 0); true }.getOrDefault(false)

    /** Ask the engine to (re)start. Cheap when it is already running. */
    fun wake(context: Context) {
        if (!installed(context)) return
        runCatching {
            val i = Intent().setComponent(ComponentName(ENGINE_PKG, ENGINE_SERVICE)).putExtra("fromGuardian", true)
            ContextCompat.startForegroundService(context.applicationContext, i)
        }.onFailure { Log.w(TAG, "Could not wake EJ Outreach engine: ${it.message}") }
    }

    /** Arms the next tick. Safe to call often (replaces the pending alarm). */
    fun schedule(context: Context) {
        val app = context.applicationContext
        if (!installed(app)) return
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(app, REQ, Intent(app, EngineGuardianReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
            .onFailure { runCatching { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) } }
    }

    /** Wake now and keep the chain going. */
    fun kick(context: Context) {
        wake(context)
        schedule(context)
    }
}

class EngineGuardianReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!(context.getSystemService(Context.USER_SERVICE) as android.os.UserManager).isUserUnlocked) return
        EngineGuardian.kick(context)
    }
}
