package com.focuskiosk.alarm

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import android.util.Log
import com.focuskiosk.admin.FocusDeviceAdminReceiver

/**
 * AlarmGuard
 * ──────────
 * Device-owner lockdown used only while an alarm is ringing:
 *  • Lock Task Mode with no features → no Home, Recents, notification shade,
 *    quick settings or power menu (so the phone cannot be switched off from the UI).
 *  • DISALLOW_SAFE_BOOT, DISALLOW_FACTORY_RESET, DISALLOW_APPS_CONTROL → no safe mode,
 *    no reset from Settings, no "Force stop" / "Clear data" for this app.
 * Restrictions that already existed (e.g. from the focus lock) are left in place on release.
 *
 * A hardware force-restart (holding the power keys ~10 s) cannot be blocked by any app;
 * AlarmStore keeps the ringing flag in Direct Boot storage so the alarm starts again on boot.
 */
object AlarmGuard {

    private const val TAG = "AlarmGuard"

    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_APPS_CONTROL
    )

    /** Lock-task features used by the focus lock when no alarm is ringing. */
    const val STANDARD_LOCK_TASK_FEATURES =
        DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
        DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
        DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
        DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isDeviceOwner(context: Context): Boolean =
        runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    fun isRinging(context: Context): Boolean = runCatching { AlarmStore.isRinging(context) }.getOrDefault(false)

    /** Applies the restrictions and allow-lists this app for lock task. Safe to call repeatedly. */
    fun engage(context: Context) {
        val ctx = context.applicationContext
        if (!isDeviceOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = FocusDeviceAdminReceiver.getComponentName(ctx)
        runCatching {
            if (!AlarmStore.isGuardEngaged(ctx)) {
                val um = ctx.getSystemService(Context.USER_SERVICE) as UserManager
                val existing = um.userRestrictions
                AlarmStore.markGuardEngaged(ctx, RESTRICTIONS.associateWith { existing.getBoolean(it, false) })
            }
            RESTRICTIONS.forEach { dpm.addUserRestriction(admin, it) }
            dpm.setLockTaskPackages(admin, arrayOf(ctx.packageName))
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            Log.i(TAG, "Alarm guard engaged.")
        }.onFailure { Log.w(TAG, "engage failed: ${it.message}") }
    }

    fun enterLockTask(activity: Activity) {
        if (!isDeviceOwner(activity)) return
        runCatching {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            if (am.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE) {
                activity.startLockTask()
            }
        }.onFailure { Log.w(TAG, "startLockTask failed: ${it.message}") }
    }

    fun release(activity: Activity?, context: Context) {
        val ctx = context.applicationContext
        activity?.let { runCatching { it.stopLockTask() } }
        if (!isDeviceOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = FocusDeviceAdminReceiver.getComponentName(ctx)
        runCatching {
            dpm.setLockTaskPackages(admin, emptyArray())
            dpm.setLockTaskFeatures(admin, STANDARD_LOCK_TASK_FEATURES)
        }
        runCatching {
            if (AlarmStore.isGuardEngaged(ctx)) {
                RESTRICTIONS.forEach { key ->
                    if (!AlarmStore.hadRestriction(ctx, key)) dpm.clearUserRestriction(admin, key)
                }
            }
            AlarmStore.clearGuard(ctx)
            Log.i(TAG, "Alarm guard released.")
        }.onFailure { Log.w(TAG, "release failed: ${it.message}") }
    }
}
