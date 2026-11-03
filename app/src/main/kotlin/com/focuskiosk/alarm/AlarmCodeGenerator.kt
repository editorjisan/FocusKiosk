package com.focuskiosk.alarm

import android.content.Context
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AlarmCodeGenerator
 * ──────────────────
 * 12-digit rotating dismissal codes shared with the PC portal (docs/index.html).
 *
 *   code(step) = HMAC-SHA256(key = password, msg = "focuskiosk:v2:<id>:<step>")
 *                → dynamic truncation to 63 bits → mod 10^12, zero-padded
 *   step       = unix seconds / 120   (a new code every 2 minutes)
 *
 * The phone accepts the last [ACCEPT_PAST_STEPS] steps plus one future step, so a code
 * stays usable for about 10 minutes — long enough to walk from the PC to the phone,
 * but useless if written down the night before. The portal reads server time, so
 * changing the PC clock does not reveal tomorrow's code.
 */
object AlarmCodeGenerator {

    const val CODE_LENGTH = 12
    const val STEP_SECONDS = 120L
    private const val ACCEPT_PAST_STEPS = 4
    private const val ACCEPT_FUTURE_STEPS = 1
    private const val MODULUS = 1_000_000_000_000L
    private const val FALLBACK_KEY = "focuskiosk"

    fun stepAt(epochMs: Long): Long = Math.floorDiv(epochMs / 1000L, STEP_SECONDS)

    private fun mac(password: String): Mac {
        val key = password.trim().ifEmpty { FALLBACK_KEY }.toByteArray(Charsets.UTF_8)
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
    }

    private fun normalizeUser(userId: String) = userId.trim().lowercase(Locale.ROOT)

    fun codeForStep(userId: String, password: String, step: Long): String {
        val hash = mac(password).doFinal("focuskiosk:v2:${normalizeUser(userId)}:$step".toByteArray(Charsets.UTF_8))
        val offset = hash[hash.size - 1].toInt() and 0x0f
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (hash[offset + i].toLong() and 0xff)
        }
        value = value and Long.MAX_VALUE
        return (value % MODULUS).toString().padStart(CODE_LENGTH, '0')
    }

    /** Four-character fingerprint shown on both phone and portal to confirm the credentials match. */
    fun keyCheck(userId: String, password: String): String {
        val hash = mac(password).doFinal("focuskiosk:v2:check:${normalizeUser(userId)}".toByteArray(Charsets.UTF_8))
        return hash.take(2).joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }

    fun keyCheck(context: Context): String =
        keyCheck(AlarmStore.portalUser(context), AlarmStore.portalPassword(context))

    fun isValid(userId: String, password: String, entered: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val clean = entered.filter { it.isDigit() }
        if (clean.length != CODE_LENGTH) return false
        val now = stepAt(nowMs)
        for (step in (now - ACCEPT_PAST_STEPS)..(now + ACCEPT_FUTURE_STEPS)) {
            val expected = codeForStep(userId, password, step)
            if (MessageDigest.isEqual(expected.toByteArray(), clean.toByteArray())) return true
        }
        return false
    }

    fun validateEnteredCode(context: Context, entered: String): Boolean =
        isValid(AlarmStore.portalUser(context), AlarmStore.portalPassword(context), entered)
}
