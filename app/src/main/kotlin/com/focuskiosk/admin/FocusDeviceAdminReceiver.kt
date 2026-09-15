package com.focuskiosk.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focuskiosk.policy.PolicyEnforcer

/**
 * FocusDeviceAdminReceiver
 * ────────────────────────
 * Central Device Admin / Device Owner receiver.
 *
 * Registered in AndroidManifest with:
 *   android:permission="android.permission.BIND_DEVICE_ADMIN"
 *
 * Activated as Device Owner via ADB (run after factory reset, before
 * adding any Google accounts):
 *   adb shell dpm set-device-owner \
 *       com.focuskiosk/.admin.FocusDeviceAdminReceiver
 *
 * As Device Owner this component unlocks the full enterprise
 * DevicePolicyManager API surface without rooting.
 */
class FocusDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "FocusDeviceAdmin"

        /**
         * Canonical ComponentName used in every DPM call.
         * Centralised here so there is exactly one source of truth.
         */
        fun getComponentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, FocusDeviceAdminReceiver::class.java)
    }

    /** Fired when this app is granted Device Admin (or Device Owner) rights. */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin ENABLED — Device Owner API surface is active.")
    }

    /**
     * Fired when admin rights are removed.
     * As Device Owner, the OS blocks programmatic removal unless
     * DevicePolicyManager.clearDeviceOwnerApp() is called first.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin DISABLED — lock enforcement lost.")
    }

    /**
     * Fired after every successful screen unlock.
     * We re-validate the expiry timestamp here to catch any attempt
     * to bypass the timer by manipulating the system clock.
     */
    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        PolicyEnforcer.validateLockState(context)
    }
}
