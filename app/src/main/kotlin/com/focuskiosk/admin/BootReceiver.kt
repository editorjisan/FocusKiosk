package com.focuskiosk.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.focuskiosk.policy.KioskRestoreManager
import com.focuskiosk.policy.PolicyEnforcer
import com.focuskiosk.service.FocusCountdownService
import com.focuskiosk.storage.SecureStorage

/**
 * BootReceiver
 * ────────────
 * DirectBoot-aware receiver for com.focuskiosk.admin.BootReceiver.
 */
class BootReceiver : BroadcastReceiver() {

    companion object { private const val TAG = "FocusBootReceiver" }

    override fun onReceive(context: Context, intent: Intent) {
        // Direct Boot: credential-encrypted SecureStorage is unreadable until the first unlock;
        // BOOT_COMPLETED follows once the user unlocks.
        if (!(context.getSystemService(Context.USER_SERVICE) as android.os.UserManager).isUserUnlocked) return
        val action = intent.action
        Log.i(TAG, "BootReceiver onReceive: $action")

        val deviceContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }

        val sp = deviceContext.getSharedPreferences("focus_kiosk_prefs", Context.MODE_PRIVATE)
        val epochTime = sp.getLong("unlock_epoch_time", 0L)
        val secureTs = runCatching { SecureStorage.getUnlockTimestampMs(context) }.getOrDefault(0L)
        val unlockEpoch = if (epochTime > 0L) epochTime else secureTs
        val isLocked = sp.getBoolean("lock_active", false) || runCatching { SecureStorage.isLockActive(context) }.getOrDefault(false)

        val now = System.currentTimeMillis()
        Log.i(TAG, "Boot check: now=$now, unlockEpoch=$unlockEpoch, isLocked=$isLocked")

        if (isLocked && (unlockEpoch in 1..now || unlockEpoch <= 0L)) {
            Log.i(TAG, "Timer expired or invalid on boot — IMMEDIATELY restoring all apps!")
            KioskRestoreManager.restoreAllApps(context)
        } else if (isLocked) {
            Log.i(TAG, "Lock still active on boot. Restarting countdown service & failsafe.")
            PolicyEnforcer.enforceOnBoot(context)
            FocusCountdownService.start(context, unlockEpoch)
            KioskRestoreManager.scheduleFailSafe(context, unlockEpoch)
        }
    }
}
