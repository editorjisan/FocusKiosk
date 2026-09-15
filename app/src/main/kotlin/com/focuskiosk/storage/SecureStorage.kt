package com.focuskiosk.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SecureStorage
 * ─────────────
 * Encrypted key/value store backed by EncryptedSharedPreferences.
 *
 * Security properties:
 *  • The MasterKey is an AES-256-GCM key stored in the Android Keystore
 *    hardware security module (TEE). It never leaves the TEE.
 *  • Both preference keys and values are encrypted at rest using AES-256-SIV
 *    (deterministic, for keys) and AES-256-GCM (randomised, for values).
 *  • Factory-resetting the device destroys the Keystore, making all stored
 *    data permanently inaccessible — this is intentional; it prevents the
 *    lock being bypassed via backup restore.
 *  • Singleton is initialised lazily and protected by double-checked locking.
 */
object SecureStorage {

    private const val PREFS_FILE = "focus_kiosk_secure_prefs"

    // ── Key constants ────────────────────────────────────────────────────────
    const val KEY_UNLOCK_TIMESTAMP_MS = "unlock_timestamp_ms"
    const val KEY_WHITELIST_PACKAGES   = "whitelist_packages"
    const val KEY_LOCK_ACTIVE          = "lock_active"
    const val KEY_BLOCK_FACTORY_RESET  = "block_factory_reset"
    const val KEY_BLOCK_USB_DEBUGGING  = "block_usb_debugging"
    const val KEY_VPN_ENABLED          = "vpn_enabled"
    const val KEY_FINANCIAL_PACKAGES   = "financial_packages"
    const val KEY_SETUP_COMPLETE       = "setup_complete"
    // ─────────────────────────────────────────────────────────────────────────

    @Volatile private var instance: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences =
        instance ?: synchronized(this) {
            instance ?: buildPrefs(context).also { instance = it }
        }

    private fun buildPrefs(context: Context): SharedPreferences {
        // Build the hardware-backed AES-256-GCM master key.
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ── Primitive accessors ───────────────────────────────────────────────────

    fun putLong(context: Context, key: String, value: Long) =
        getPrefs(context).edit().putLong(key, value).apply()

    fun getLong(context: Context, key: String, default: Long = 0L): Long =
        getPrefs(context).getLong(key, default)

    fun putBoolean(context: Context, key: String, value: Boolean) =
        getPrefs(context).edit().putBoolean(key, value).apply()

    fun getBoolean(context: Context, key: String, default: Boolean = false): Boolean =
        getPrefs(context).getBoolean(key, default)

    fun putString(context: Context, key: String, value: String) =
        getPrefs(context).edit().putString(key, value).apply()

    fun getString(context: Context, key: String, default: String = ""): String =
        getPrefs(context).getString(key, default) ?: default

    /**
     * Persists a package-name set as a pipe-delimited sorted string.
     * We avoid SharedPreferences StringSet because its ordering is undefined
     * and it cannot be encrypted key-by-key with AES-256-SIV reliably.
     */
    fun putPackageSet(context: Context, key: String, packages: Set<String>) =
        putString(context, key, packages.sorted().joinToString("|"))

    fun getPackageSet(context: Context, key: String): Set<String> {
        val raw = getString(context, key)
        return if (raw.isBlank()) emptySet() else raw.split("|").toSet()
    }

    // ── Convenience helpers ───────────────────────────────────────────────────

    fun isLockActive(context: Context): Boolean =
        getBoolean(context, KEY_LOCK_ACTIVE)

    fun getUnlockTimestampMs(context: Context): Long =
        getLong(context, KEY_UNLOCK_TIMESTAMP_MS)

    fun isUnlockTimePassed(context: Context): Boolean =
        System.currentTimeMillis() >= getUnlockTimestampMs(context)

    fun setSetupCompleted(context: Context, completed: Boolean) =
        putBoolean(context, KEY_SETUP_COMPLETE, completed)

    fun isSetupCompleted(context: Context): Boolean =
        getBoolean(context, KEY_SETUP_COMPLETE, false)
}
