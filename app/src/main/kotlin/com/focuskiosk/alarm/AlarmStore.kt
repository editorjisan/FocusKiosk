package com.focuskiosk.alarm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.security.SecureRandom
import com.focuskiosk.storage.SecureStorage

/**
 * AlarmStore
 * ──────────
 * Alarm state lives in device-protected (Direct Boot) storage, like the stock Clock app,
 * so a ringing alarm resumes right after a forced reboot — even before the phone is unlocked.
 * Everything in [SecureStorage] is credential-encrypted and unreadable until first unlock.
 */
object AlarmStore {

    private const val TAG = "AlarmStore"
    private const val PREFS = "focus_alarm_store"

    private const val KEY_ALARMS = "alarms_json"
    private const val KEY_PORTAL_USER = "portal_user"
    private const val KEY_PORTAL_PASS = "portal_pass" // pre-1.5.1 fixed password, removed on first use
    private const val KEY_ONE_TIME_PASSWORD = "one_time_password"
    private const val KEY_RINGING = "ringing"
    private const val KEY_RINGING_ALARM_ID = "ringing_alarm_id"
    private const val KEY_RINGING_SINCE = "ringing_since"
    private const val KEY_NEXT_ALARM_MS = "next_alarm_ms"
    private const val KEY_NEXT_ALARM_ID = "next_alarm_id"
    private const val KEY_MIGRATED = "migrated_v150"
    private const val KEY_GUARD_PREFIX = "guard_had_"
    private const val KEY_GUARD_ENGAGED = "guard_engaged"

    const val DEFAULT_PORTAL_USER = "jisan"
    const val PORTAL_URL = "https://editorjisan.github.io/FocusKiosk/"
    const val PORTAL_URL_DISPLAY = "editorjisan.github.io/FocusKiosk"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Alarms ────────────────────────────────────────────────────────────────

    fun getAlarms(context: Context): List<AlarmItem> =
        AlarmItem.listFromJsonString(prefs(context).getString(KEY_ALARMS, "") ?: "")
            .sortedWith(compareBy({ it.hour }, { it.minute }))

    fun saveAlarms(context: Context, list: List<AlarmItem>) {
        prefs(context).edit().putString(KEY_ALARMS, AlarmItem.listToJsonString(list)).commit()
    }

    // ── PC portal credentials ─────────────────────────────────────────────────

    fun portalUser(context: Context): String =
        prefs(context).getString(KEY_PORTAL_USER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_PORTAL_USER

    fun setPortalUser(context: Context, user: String) {
        prefs(context).edit().putString(KEY_PORTAL_USER, user.trim()).remove(KEY_PORTAL_PASS).commit()
    }

    // ── One-time portal password: a fresh one for every alarm, gone once it is dismissed ──

    private const val OTP_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ" // no 0/O, 1/I/L
    private const val OTP_LENGTH = 8

    fun oneTimePassword(context: Context): String? =
        prefs(context).getString(KEY_ONE_TIME_PASSWORD, null)?.takeIf { it.length == OTP_LENGTH }

    /** Returns the password for the alarm that is ringing, creating it when the alarm starts. */
    fun ensureOneTimePassword(context: Context): String {
        oneTimePassword(context)?.let { return it }
        val random = SecureRandom()
        val otp = (1..OTP_LENGTH).map { OTP_ALPHABET[random.nextInt(OTP_ALPHABET.length)] }.joinToString("")
        prefs(context).edit().putString(KEY_ONE_TIME_PASSWORD, otp).remove(KEY_PORTAL_PASS).commit()
        return otp
    }

    fun clearOneTimePassword(context: Context) {
        prefs(context).edit().remove(KEY_ONE_TIME_PASSWORD).commit()
    }

    fun formatPassword(otp: String): String = otp.chunked(4).joinToString("-")

    // ── Ringing state (commit() so it survives an immediate forced reboot) ───────

    fun isRinging(context: Context): Boolean = prefs(context).getBoolean(KEY_RINGING, false)

    fun ringingAlarmId(context: Context): String? = prefs(context).getString(KEY_RINGING_ALARM_ID, null)

    fun ringingSince(context: Context): Long = prefs(context).getLong(KEY_RINGING_SINCE, 0L)

    fun setRinging(context: Context, ringing: Boolean, alarmId: String? = null) {
        val edit = prefs(context).edit().putBoolean(KEY_RINGING, ringing)
        if (ringing) {
            edit.putString(KEY_RINGING_ALARM_ID, alarmId)
            if (!isRinging(context)) edit.putLong(KEY_RINGING_SINCE, System.currentTimeMillis())
        } else {
            edit.remove(KEY_RINGING_ALARM_ID).remove(KEY_RINGING_SINCE)
        }
        edit.commit()
    }

    fun nextAlarmMs(context: Context): Long = prefs(context).getLong(KEY_NEXT_ALARM_MS, 0L)

    fun setNextAlarm(context: Context, alarmId: String?, triggerMs: Long) {
        prefs(context).edit().putString(KEY_NEXT_ALARM_ID, alarmId).putLong(KEY_NEXT_ALARM_MS, triggerMs).apply()
    }

    // ── AlarmGuard bookkeeping: which restrictions existed before ringing ───────

    fun isGuardEngaged(context: Context): Boolean = prefs(context).getBoolean(KEY_GUARD_ENGAGED, false)

    fun markGuardEngaged(context: Context, hadRestrictions: Map<String, Boolean>) {
        val edit = prefs(context).edit().putBoolean(KEY_GUARD_ENGAGED, true)
        hadRestrictions.forEach { (key, had) -> edit.putBoolean(KEY_GUARD_PREFIX + key, had) }
        edit.commit()
    }

    fun hadRestriction(context: Context, key: String): Boolean =
        prefs(context).getBoolean(KEY_GUARD_PREFIX + key, false)

    fun clearGuard(context: Context) {
        val p = prefs(context)
        val edit = p.edit().remove(KEY_GUARD_ENGAGED)
        p.all.keys.filter { it.startsWith(KEY_GUARD_PREFIX) }.forEach { edit.remove(it) }
        edit.commit()
    }

    /**
     * One-time copy of alarms and the portal ID from the pre-1.5 encrypted store.
     * Must run only after the user has unlocked (SecureStorage is credential-encrypted).
     */
    fun migrateFromSecureStorage(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        runCatching {
            val legacyJson = SecureStorage.getString(context, SecureStorage.KEY_MULTI_ALARMS_JSON, "")
            val masterEnabled = SecureStorage.getBoolean(context, SecureStorage.KEY_ALARM_ENABLED, false)
            val legacy = AlarmItem.listFromJsonString(legacyJson).map {
                it.copy(isEnabled = it.isEnabled && masterEnabled)
            }
            val user = SecureStorage.getString(context, SecureStorage.KEY_ALARM_USER_ID, "")
            val edit = p.edit()
            if (legacy.isNotEmpty() && (p.getString(KEY_ALARMS, "") ?: "").isBlank()) {
                edit.putString(KEY_ALARMS, AlarmItem.listToJsonString(legacy))
            }
            if (user.isNotBlank()) edit.putString(KEY_PORTAL_USER, user)
            edit.putBoolean(KEY_MIGRATED, true).commit()
            Log.i(TAG, "Migrated ${legacy.size} legacy alarms (master enabled=$masterEnabled).")
        }.onFailure { Log.w(TAG, "Legacy alarm migration failed: ${it.message}") }
    }
}
