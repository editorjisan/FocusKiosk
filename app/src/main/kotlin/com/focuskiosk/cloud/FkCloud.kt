package com.focuskiosk.cloud

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.focuskiosk.alarm.AlarmStore
import com.focuskiosk.policy.WorkWindowScheduler
import com.focuskiosk.storage.SecureStorage
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * FkCloud — exact-time wake-ups from the cloud.
 * ─────────────────────────────────────────────
 * XOS "Hiber" freezes FocusKiosk a few seconds after it goes idle and HOLDS BACK its alarms until
 * something thaws it (that is why the upload window / wake-up alarm only went off when the app was
 * opened). A Firebase push is always let through and thaws the app. So every time an alarm is armed,
 * the upcoming times are sent to the EJ cloud timer (Cloudflare Worker), which pushes this app at
 * exactly those minutes; [FkPushService] then opens/closes the window or rings the alarm.
 */
object FkCloud {
    private const val TAG = "FkCloud"
    private const val ENDPOINT = "https://ej-outreach-push.mdjubayarahamedjisan62.workers.dev/fk"
    private const val CLIENT_TAG = "fk-wake-v1"
    private const val PREFS = "fk_cloud"
    private const val DEBUG_PKG = "com.focuskiosk.debug"
    private const val RESEND_MS = 6 * 3_600_000L

    private val thread by lazy { HandlerThread("FkCloud").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }
    private val sendRunnable = Runnable { sendNow() }
    @Volatile private var appCtx: Context? = null

    /** Only the build that runs on the phone is registered with Firebase. */
    fun supported(context: Context) = context.packageName == DEBUG_PKG

    fun init(context: Context) {
        val app = context.applicationContext
        appCtx = app
        if (!supported(app)) return
        runCatching {
            if (FirebaseApp.getApps(app).isEmpty()) {
                FirebaseApp.initializeApp(app, FirebaseOptions.Builder()
                    .setApplicationId("1:548261837949:android:558ea28d3fbfa270b9c514")
                    .setApiKey("AIzaSyCuKtOnrjjVRxeEMjjnuVSb9fdVZO8n3iE")
                    .setProjectId("ej-outreach")
                    .setGcmSenderId("548261837949")
                    .build())
            }
            FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
                if (!t.isNullOrBlank()) saveToken(app, t)
            }
        }.onFailure { Log.w(TAG, "Firebase init failed: ${it.message}") }
        report(app)
    }

    fun saveToken(context: Context, token: String) {
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString("token", "") != token) p.edit().putString("token", token).putString("sent", "").apply()
        report(app)
    }

    /** Every upcoming moment FocusKiosk must act at: upload window open/close and the next wake-up alarm. */
    fun upcomingTimes(context: Context, now: Long = System.currentTimeMillis()): List<Long> {
        val app = context.applicationContext
        val out = mutableListOf<Long>()
        runCatching {
            if (SecureStorage.isLockActive(app) && WorkWindowScheduler.isEnabled(app)) {
                val s = WorkWindowScheduler.calculateWindowSchedule(app, now)
                if (WorkWindowScheduler.isWindowActive(app)) {
                    out += SecureStorage.getLong(app, SecureStorage.KEY_NEXT_WINDOW_END_MS, 0L)
                }
                if (s.isCurrentlyActive) out += s.currentWindowEndMs
                out += s.nextWindowStartMs
                out += s.nextWindowEndMs
            }
        }
        runCatching { out += AlarmStore.nextAlarmMs(app) }
        return out.filter { it > now - 60_000L && it < now + 40L * 86_400_000L }.distinct().sorted().take(12)
    }

    /** Debounced: many alarm changes in a row cause one upload. */
    fun report(context: Context) {
        val app = context.applicationContext
        appCtx = app
        if (!supported(app)) return
        handler.removeCallbacks(sendRunnable)
        handler.postDelayed(sendRunnable, 1_500L)
    }

    private fun sendNow() {
        val app = appCtx ?: return
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = p.getString("token", "").orEmpty()
        if (token.isEmpty()) return
        val times = upcomingTimes(app)
        val body = JSONObject().put("token", token).put("times", JSONArray(times)).put("tz", java.util.TimeZone.getDefault().id)
        val sig = "$token|${times.joinToString(",")}"
        if (sig == p.getString("sent", "") && System.currentTimeMillis() - p.getLong("sentAt", 0L) < RESEND_MS) return
        runCatching {
            val req = Request.Builder().url(ENDPOINT)
                .header("X-FK", CLIENT_TAG)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { r ->
                if (r.isSuccessful) {
                    p.edit().putString("sent", sig).putLong("sentAt", System.currentTimeMillis()).apply()
                    Log.i(TAG, "Cloud wake times saved: $times")
                } else {
                    Log.w(TAG, "Cloud refused wake times: HTTP ${r.code}")
                    handler.postDelayed(sendRunnable, 60_000L)
                }
            }
        }.onFailure {
            Log.w(TAG, "Could not reach cloud timer: ${it.message}")
            handler.postDelayed(sendRunnable, 60_000L)
        }
    }
}
