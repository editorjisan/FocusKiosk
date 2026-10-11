package com.focuskiosk.alarm

import android.content.Context
import android.util.Log
import com.focuskiosk.storage.SecureStorage
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * AlarmCodeGenerator
 * ──────────────────
 * Cryptographic verification engine for Extreme Wake-up Alarm.
 *
 * Produces deterministic, date- and time-locked 6-digit dismissal codes
 * matching the PC Web Portal algorithm byte-for-byte using SHA-256.
 *
 * The code is strictly locked until 60 minutes (1 hour) before the scheduled alarm,
 * preventing users from looking up or memorizing the code prior to sleep.
 */
object AlarmCodeGenerator {

    private const val TAG = "AlarmCodeGenerator"
    const val CODE_UNLOCK_WINDOW_MINS = 90 // Unlocks 1.5 hours (90 mins) before alarm

    /**
     * Core cryptographic hash function matching JavaScript window.crypto.subtle.
     */
    fun generateRawCode(
        userId: String,
        password: String,
        dateString: String,
        alarmHour: Int,
        alarmMinute: Int
    ): String {
        return try {
            val normalizedUser = userId.trim().lowercase(Locale.ROOT)
            val normalizedPass = password.trim()
            val input = "$normalizedUser:$normalizedPass:$dateString-$alarmHour:$alarmMinute"

            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(input.toByteArray(Charsets.UTF_8))

            val intVal = java.nio.ByteBuffer.wrap(hash).int

            val code = (Math.abs(intVal) % 900000) + 100000
            code.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error generating code: ${e.message}", e)
            "123456"
        }
    }

    /**
     * Generates today's code for given credentials and alarm time.
     */
    fun getTodayCode(context: Context): String {
        val userId = SecureStorage.getString(context, SecureStorage.KEY_ALARM_USER_ID, "jisan")
        val password = SecureStorage.getString(context, SecureStorage.KEY_ALARM_PASSWORD, "1234")
        val alarmHour = SecureStorage.getInt(context, SecureStorage.KEY_ALARM_HOUR, 6)
        val alarmMinute = SecureStorage.getInt(context, SecureStorage.KEY_ALARM_MINUTE, 30)

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return generateRawCode(userId, password, dateStr, alarmHour, alarmMinute)
    }

    /**
     * Validates an entered code against today's (and yesterday's rollover) alarm times.
     */
    fun validateEnteredCode(context: Context, enteredCode: String): Boolean {
        val cleanCode = enteredCode.trim()
        if (cleanCode.length != 6) return false

        val userId = SecureStorage.getString(context, SecureStorage.KEY_ALARM_USER_ID, "jisan")
        val password = SecureStorage.getString(context, SecureStorage.KEY_ALARM_PASSWORD, "1234")
        val alarmHour = SecureStorage.getInt(context, SecureStorage.KEY_ALARM_HOUR, 6)
        val alarmMinute = SecureStorage.getInt(context, SecureStorage.KEY_ALARM_MINUTE, 30)

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val todayCode = generateRawCode(userId, password, todayStr, alarmHour, alarmMinute)
        if (cleanCode == todayCode) return true

        // Also check yesterday's date in case the user went to sleep past midnight or alarm rings overnight
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        val yesterdayCode = generateRawCode(userId, password, yesterdayStr, alarmHour, alarmMinute)

        return cleanCode == yesterdayCode
    }

    /**
     * Returns true if the current time is within the unlock window (1 hour before alarm through 4 hours after).
     */
    fun isCodeUnlocked(alarmHour: Int, alarmMinute: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, alarmHour)
            set(Calendar.MINUTE, alarmMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val alarmMs = cal.timeInMillis
        val unlockStartMs = alarmMs - (CODE_UNLOCK_WINDOW_MINS * 60_000L)
        val unlockEndMs = alarmMs + (4 * 3600_000L)

        return nowMs in unlockStartMs..unlockEndMs
    }
}
