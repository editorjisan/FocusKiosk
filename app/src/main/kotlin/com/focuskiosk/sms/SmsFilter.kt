package com.focuskiosk.sms

import android.content.Context
import android.net.Uri
import com.focuskiosk.storage.SecureStorage

/**
 * SmsFilter
 * ─────────
 * Keeps promotional SMS (GP / Robi offers, ads, bundle spam) out of the notification shade while
 * ALWAYS letting real messages through (OTP, bKash/bank alerts, verification codes, people).
 *
 * Modes: OFF (everything shows) · PROMO (auto-hide promos, per-sender overrides) · CUSTOM (only senders you muted).
 * The master switch is the "Filter Off" button — one tap unlocks everything again.
 */
object SmsFilter {
    const val KEY_ENABLED = "sms_filter_enabled"
    const val KEY_AUTO_PROMO = "sms_filter_auto_promo"
    const val KEY_MUTED = "sms_filter_muted"     // senders the user muted
    const val KEY_ALLOWED = "sms_filter_allowed" // senders the user force-allowed

    val MESSAGING_PACKAGES = setOf(
        "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.mms", "com.android.messaging", "com.miui.mms", "com.oplus.mms", "com.coloros.mms"
    )

    // Never filtered, whatever the sender looks like.
    private val SAFE_BODY = Regex(
        "otp|verification|verify|confirmation code|your code|passcode|credited|debited|deposit|" +
            "transaction|declined|balance|cash ?out|cash ?in|received|sent tk|payment|login|" +
            "ওটিপি|কোড|ভেরিফিকেশন|জমা|ক্রেডিট|ডেবিট|ট্রানজ্যাকশন|লেনদেন",
        RegexOption.IGNORE_CASE
    )
    private val SAFE_SENDER = Regex(
        "bkash|nagad|rocket|upay|google|facebook|meta|instagram|whatsapp|telegram|tiktok|apple|microsoft|amazon|" +
            "paypal|wise|payoneer|binance|bank|ucb|brac|dbbl|city|ific|ebl|mtb|sonali|islami|cloudotp|otp|" +
            "bdtickets|grameenphone care|nid|nbr|govt",
        RegexOption.IGNORE_CASE
    )
    private val PROMO_SENDER = Regex(
        "^(gp|mygp|my gp|robi|myrobi|banglalink|bl |airtel|teletalk|btrc|flexiload|my usage)|" +
            "gp ?\\d|rc\\d+tk|\\d+gb|bundle|offer|special|1poisha|90min|320min|promo|udvash|rapid ?net|" +
            "^(1213|21283|12\\d\\d|2\\d{4})$",
        RegexOption.IGNORE_CASE
    )

    fun isEnabled(c: Context) = SecureStorage.getBoolean(c, KEY_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) = SecureStorage.putBoolean(c, KEY_ENABLED, on)
    fun isAutoPromo(c: Context) = SecureStorage.getBoolean(c, KEY_AUTO_PROMO, true)
    fun setAutoPromo(c: Context, on: Boolean) = SecureStorage.putBoolean(c, KEY_AUTO_PROMO, on)

    private fun norm(s: String) = s.trim().lowercase()
    fun muted(c: Context): Set<String> = SecureStorage.getPackageSet(c, KEY_MUTED)
    fun allowed(c: Context): Set<String> = SecureStorage.getPackageSet(c, KEY_ALLOWED)

    fun setSender(c: Context, sender: String, hide: Boolean) {
        val k = norm(sender)
        val m = muted(c).toMutableSet().apply { if (hide) add(k) else remove(k) }
        val a = allowed(c).toMutableSet().apply { if (hide) remove(k) else add(k) }
        SecureStorage.putPackageSet(c, KEY_MUTED, m)
        SecureStorage.putPackageSet(c, KEY_ALLOWED, a)
    }

    fun looksPromo(sender: String, body: String = ""): Boolean {
        if (body.isNotEmpty() && SAFE_BODY.containsMatchIn(body)) return false
        if (SAFE_SENDER.containsMatchIn(sender)) return false
        return PROMO_SENDER.containsMatchIn(sender.trim())
    }

    /** Should this sender's notification be hidden right now? */
    fun shouldHide(c: Context, sender: String, body: String): Boolean {
        if (!isEnabled(c)) return false
        val k = norm(sender)
        if (k in muted(c)) return true
        if (k in allowed(c)) return false
        if (body.isNotEmpty() && SAFE_BODY.containsMatchIn(body)) return false
        return isAutoPromo(c) && looksPromo(sender, body)
    }

    /** Whether the sender is currently muted in the list UI (explicit mute, or auto-promo and not force-allowed). */
    fun isHidden(c: Context, sender: String, sampleBody: String): Boolean {
        val k = norm(sender)
        if (k in muted(c)) return true
        if (k in allowed(c)) return false
        return isAutoPromo(c) && looksPromo(sender, sampleBody)
    }

    data class Sender(val name: String, val count: Int, val sample: String, val lastMs: Long)

    /** Distinct senders from the SMS inbox (needs READ_SMS, granted silently by the device owner). */
    fun senders(c: Context): List<Sender> {
        val map = LinkedHashMap<String, Sender>()
        runCatching {
            c.contentResolver.query(
                Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"),
                null, null, "date DESC LIMIT 1500"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val addr = cur.getString(0)?.trim().orEmpty()
                    if (addr.isEmpty()) continue
                    val k = norm(addr)
                    val prev = map[k]
                    map[k] = if (prev == null) Sender(addr, 1, cur.getString(1).orEmpty().take(80), cur.getLong(2))
                    else prev.copy(count = prev.count + 1)
                }
            }
        }
        return map.values.toList()
    }
}
