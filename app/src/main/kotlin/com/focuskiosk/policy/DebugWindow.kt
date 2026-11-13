package com.focuskiosk.policy

import android.content.Context
import android.util.Log

/**
 * One-time 2-hour USB debugging window (v1.6.0, asked for by Jisan to fix the EJ Outreach phone engine).
 * Opens once when this version first runs, then closes itself: the 1-minute EngineGuardian tick and every
 * focus-lock re-apply put the USB-debugging block back as soon as the 2 hours are over.
 */
object DebugWindow {
    private const val TAG = "DebugWindow"
    private const val PREFS = "focuskiosk_debug_window"
    private const val GRANT_ID = "v1.6.0-usb-2h"
    private const val LENGTH_MS = 2L * 60 * 60 * 1000

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOpen(context: Context): Boolean = System.currentTimeMillis() < prefs(context).getLong("until", 0L)

    /** Opens the window the first time this version runs. Never opens twice. */
    fun openOnceForThisVersion(context: Context) {
        val p = prefs(context)
        if (p.getString("granted", null) == GRANT_ID) return
        val until = System.currentTimeMillis() + LENGTH_MS
        p.edit().putString("granted", GRANT_ID).putLong("until", until).putBoolean("closed", false).apply()
        PolicyEnforcer.enableUsbDebugging(context)
        Log.i(TAG, "USB debugging opened for 2 hours (until $until)")
    }

    /** Called every minute: when the 2 hours are over, put the block back (if the lock wants it). */
    fun closeIfExpired(context: Context) {
        val p = prefs(context)
        if (p.getString("granted", null) != GRANT_ID || p.getBoolean("closed", false) || isOpen(context)) return
        val blockUsb = com.focuskiosk.storage.SecureStorage.getBoolean(
            context, com.focuskiosk.storage.SecureStorage.KEY_BLOCK_USB_DEBUGGING, true)
        if (blockUsb) PolicyEnforcer.disableUsbDebugging(context)
        p.edit().putBoolean("closed", true).apply()
        Log.i(TAG, "2-hour USB debugging window closed")
    }
}
