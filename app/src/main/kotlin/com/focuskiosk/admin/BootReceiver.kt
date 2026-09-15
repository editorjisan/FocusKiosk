package com.focuskiosk.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focuskiosk.policy.PolicyEnforcer

/**
 * BootReceiver
 * ────────────
 * Listens for BOOT_COMPLETED and LOCKED_BOOT_COMPLETED.
 *
 * Why this is needed:
 *  Some OEM ROMs (Samsung OneUI, MIUI) silently reset package suspension
 *  flags on reboot. Re-applying enforcement ensures no blocked app
 *  becomes accessible after a restart.
 *
 * Also re-validates the unlock timestamp to auto-release if the timer
 * expired while the device was off.
 */
class BootReceiver : BroadcastReceiver() {

    companion object { private const val TAG = "FocusBootReceiver" }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> {
                Log.i(TAG, "Boot detected — re-enforcing focus policies.")
                PolicyEnforcer.enforceOnBoot(context)
            }
        }
    }
}
