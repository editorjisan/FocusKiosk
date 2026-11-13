package com.focuskiosk.policy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * a no-op. On top of that it WATCHES the engine (a service binding whose death is reported the
 * moment the app is force-stopped, the package-restarted broadcast, and the engine's notification
 * disappearing), so a swipe-kill is undone within ~2 seconds; the 1-minute alarm is only the backup. A user-pressed "Stop" is respected
 * (the engine checks the "fromGuardian" extra).
 */
object EngineGuardian {
    private const val TAG = "EngineGuardian"
    private const val ENGINE_PKG = PolicyEnforcer.OUTREACH_ENGINE_PACKAGE
    private const val ENGINE_SERVICE = "id.flutter.flutter_background_service.BackgroundService"
    private const val INTERVAL_MS = 60_000L
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

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var bound = false
    @Volatile private var watching = false
    private var lastRevive = 0L

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) { Log.i(TAG, "Engine process died"); appCtx?.let { revive(it, "process died") } }
        override fun onBindingDied(name: ComponentName?) { Log.i(TAG, "Engine binding died (force-stop)"); appCtx?.let { unbind(it); revive(it, "force-stopped") } }
        override fun onNullBinding(name: ComponentName?) {}
    }
    @Volatile private var appCtx: Context? = null

    private val restartReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.data?.schemeSpecificPart == ENGINE_PKG) revive(c.applicationContext, "package restarted")
        }
    }

    private fun bind(ctx: Context) {
        if (bound || !installed(ctx)) return
        // flags = 0: never creates the service by itself (a user "Stop" stays stopped), only watches it
        bound = runCatching { ctx.bindService(Intent().setComponent(ComponentName(ENGINE_PKG, ENGINE_SERVICE)), conn, 0) }.getOrDefault(false)
    }

    private fun unbind(ctx: Context) {
        if (!bound) return
        runCatching { ctx.unbindService(conn) }
        bound = false
    }

    /** Engine just died: start it again right away (after the system finishes the force-stop). */
    fun revive(context: Context, why: String) {
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        if (now - lastRevive < 1500) return
        lastRevive = now
        Log.i(TAG, "Reviving EJ Outreach engine: $why")
        main.postDelayed({ wake(app); unbind(app); bind(app) }, 1200)
        main.postDelayed({ wake(app); bind(app) }, 6000)  // second try in case HiOS was still cleaning up
    }

    // XOS "Hiber" freezes EJ Outreach and swallows its own alarms; every service start thaws it for ~5 s,
    // which is enough for it to report in. FocusKiosk stays awake (VPN traffic), so a plain 60-s loop here
    // keeps the engine alive even when this app's own alarms are held back.
    private val pulse = object : Runnable {
        override fun run() {
            appCtx?.let { wake(it) }
            main.postDelayed(this, INTERVAL_MS)
        }
    }

    /** Starts watching the engine for as long as this process lives. Safe to call often. */
    fun watch(context: Context) {
        val app = context.applicationContext
        appCtx = app
        main.post {
            if (!watching) {
                watching = true
                main.postDelayed(pulse, INTERVAL_MS)
                val f = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_RESTARTED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                }
                runCatching {
                    if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(restartReceiver, f, Context.RECEIVER_EXPORTED)
                    else app.registerReceiver(restartReceiver, f)
                }
            }
            bind(app)
        }
    }

    /** Wake now and keep the chain going. */
    fun kick(context: Context) {
        wake(context)
        schedule(context)
        watch(context)
    }
}

class EngineGuardianReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!(context.getSystemService(Context.USER_SERVICE) as android.os.UserManager).isUserUnlocked) return
        EngineGuardian.kick(context)
        runCatching { DebugWindow.closeIfExpired(context) }
    }
}
