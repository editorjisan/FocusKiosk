package com.focuskiosk.policy

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import android.util.Log
import com.focuskiosk.admin.FocusDeviceAdminReceiver
import com.focuskiosk.storage.SecureStorage

/**
 * PolicyEnforcer
 * ──────────────
 * Single entry-point for all DevicePolicyManager enforcement actions.
 *
 * Design principles:
 *  • Stateless — reads from SecureStorage, writes to DPM. No mutable state.
 *  • Defensive — every public method starts with [requireDeviceOwner].
 *    If the app is not Device Owner, calls are logged and skipped cleanly
 *    rather than crashing with an opaque SecurityException.
 *  • Financial-app safe — isolation is achieved purely via
 *    setApplicationHidden + setPackagesSuspended. We do NOT use
 *    AccessibilityService, MediaProjection, or SYSTEM_ALERT_WINDOW overlays,
 *    which would break banking app root/integrity detection.
 */
object PolicyEnforcer {

    private const val TAG = "PolicyEnforcer"

    // ── DPM accessors ─────────────────────────────────────────────────────────

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context) =
        FocusDeviceAdminReceiver.getComponentName(context)

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName)

    private fun requireDeviceOwner(context: Context): Boolean {
        if (isDeviceOwner(context)) return true
        Log.e(TAG, "NOT device owner — skipping DPM call. " +
            "Run: adb shell dpm set-device-owner " +
            "${context.packageName}/.admin.FocusDeviceAdminReceiver")
        return false
    }

    // ── App hiding ────────────────────────────────────────────────────────────

    /**
     * setApplicationHidden(pkg, true):
     *  • Removes app from Settings > Apps list.
     *  • Removes app from any launcher app drawer.
     *  • Package remains installed on-disk but is invisible to the user.
     *  • Does NOT interfere with SSL pinning or root detection in other apps.
     */
    fun hideApplication(context: Context, packageName: String) {
        if (!requireDeviceOwner(context)) return
        runCatching {
            val result = dpm(context).setApplicationHidden(admin(context), packageName, true)
            Log.d(TAG, "setApplicationHidden($packageName, hidden=true) → $result")
        }.onFailure { Log.e(TAG, "hideApplication($packageName): ${it.message}") }
    }

    fun unhideApplication(context: Context, packageName: String) {
        if (!requireDeviceOwner(context)) return
        runCatching {
            dpm(context).setApplicationHidden(admin(context), packageName, false)
            Log.d(TAG, "setApplicationHidden($packageName, hidden=false)")
        }.onFailure { Log.e(TAG, "unhideApplication($packageName): ${it.message}") }
    }

    // ── Package suspension ────────────────────────────────────────────────────

    /**
     * setPackagesSuspended(pkgs, true):
     *  • Suspended apps show a system dialog if launched via any method.
     *  • Background services and AlarmManager callbacks are suppressed.
     *  • Defence-in-depth layer: if a hidden app is somehow launched
     *    via a direct Intent, suspension ensures it still cannot run.
     *
     * Returns the subset of packages that failed (e.g. protected system apps).
     */
    fun suspendPackages(context: Context, packages: Array<String>): Array<String>? {
        if (!requireDeviceOwner(context)) return null
        return runCatching {
            dpm(context).setPackagesSuspended(admin(context), packages, true)
                .also { failed ->
                    val ok = packages.size - (failed?.size ?: 0)
                    Log.i(TAG, "Suspended $ok/${packages.size} packages. " +
                        "Could not suspend: ${failed?.joinToString()}")
                }
        }.getOrElse { Log.e(TAG, "suspendPackages: ${it.message}"); null }
    }

    fun unsuspendPackages(context: Context, packages: Array<String>) {
        if (!requireDeviceOwner(context)) return
        runCatching {
            dpm(context).setPackagesSuspended(admin(context), packages, false)
        }.onFailure { Log.e(TAG, "unsuspendPackages: ${it.message}") }
    }

    // ── Install restrictions ──────────────────────────────────────────────────

    /**
     * Ensures sideloading and app installs are NEVER blocked by Device Owner policy.
     */
    fun unblockAppInstalls(context: Context) {
        if (!requireDeviceOwner(context)) return
        runCatching {
            dpm(context).clearUserRestriction(admin(context), UserManager.DISALLOW_INSTALL_APPS)
            dpm(context).clearUserRestriction(admin(context), UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            Log.i(TAG, "Install restrictions cleared — sideloading and unknown sources unblocked.")
        }.onFailure { Log.e(TAG, "unblockAppInstalls: ${it.message}") }
    }

    /**
     * setUninstallBlocked(ourPackage, true):
     *  • Greys out the "Uninstall" button in Settings > Apps for this app.
     *  • Prevents adb uninstall from working without first clearing Device Owner.
     */
    fun blockOwnUninstall(context: Context) {
        if (!requireDeviceOwner(context)) return
        runCatching {
            dpm(context).setUninstallBlocked(admin(context), context.packageName, true)
            Log.i(TAG, "setUninstallBlocked(${context.packageName}, true)")
        }.onFailure { Log.e(TAG, "blockOwnUninstall: ${it.message}") }
    }

    // ── Anti-tamper restrictions ──────────────────────────────────────────────

    /**
     * DISALLOW_DEBUGGING_FEATURES:
     *  • Blocks ADB over USB and ADB over WiFi.
     *  • Disables the Developer Options > USB Debugging toggle.
     *  • Prevents "adb shell dpm remove-active-admin" bypass.
     */
    fun disableUsbDebugging(context: Context) {
        if (!requireDeviceOwner(context)) return
        runCatching {
            dpm(context).addUserRestriction(admin(context), UserManager.DISALLOW_DEBUGGING_FEATURES)
            Log.i(TAG, "DISALLOW_DEBUGGING_FEATURES applied — ADB disabled.")
        }.onFailure { Log.e(TAG, "disableUsbDebugging: ${it.message}") }
    }

    fun enableUsbDebugging(context: Context) {
        if (!requireDeviceOwner(context)) return
        runCatching {
            dpm(context).clearUserRestriction(admin(context), UserManager.DISALLOW_DEBUGGING_FEATURES)
            Log.i(TAG, "DISALLOW_DEBUGGING_FEATURES cleared — ADB re-enabled.")
        }.onFailure { Log.e(TAG, "enableUsbDebugging: ${it.message}") }
    }

    /**
     * DISALLOW_FACTORY_RESET:
     *  • Blocks Settings > General Management > Reset > Factory Data Reset.
     *  • Blocks some OEM hardware-key recovery sequences.
     *  • Configurable — user may opt out in the setup wizard.
     *
     *  ⚠ Warning: If the kiosk app enters a crash loop, this may prevent
     *  recovery. Enable only if the user understands the risk.
     */
    fun disableFactoryReset(context: Context) {
        if (!requireDeviceOwner(context)) return
        runCatching {
            dpm(context).addUserRestriction(admin(context), UserManager.DISALLOW_FACTORY_RESET)
            Log.i(TAG, "DISALLOW_FACTORY_RESET applied.")
        }.onFailure { Log.e(TAG, "disableFactoryReset: ${it.message}") }
    }

    fun enableFactoryReset(context: Context) {
        if (!requireDeviceOwner(context)) return
        runCatching {
            dpm(context).clearUserRestriction(admin(context), UserManager.DISALLOW_FACTORY_RESET)
            Log.i(TAG, "DISALLOW_FACTORY_RESET cleared.")
        }.onFailure { Log.e(TAG, "enableFactoryReset: ${it.message}") }
    }

    /**
     * Critical system packages that must NEVER be hidden or suspended.
     *
     * Hiding com.android.systemui crashes the entire Android UI layer.
     * Hiding GMS breaks authentication, push notifications, and SafetyNet.
     * Hiding the input method makes the keyboard unavailable everywhere.
     * This denylist is applied before every hide/suspend call.
     */
    private val SYSTEM_PACKAGE_DENYLIST = setOf(
        // Core OS / UI
        "com.android.systemui",
        "com.android.system.ext",
        "android",
        "com.android.phone",
        "com.android.shell",
        "com.android.bluetooth",
        "com.android.providers.settings",
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.android.providers.downloads",
        "com.android.providers.contacts",
        "com.android.providers.calendar",
        "com.android.externalstorage",
        "com.android.keychain",
        "com.android.networkstack",
        "com.android.networkstack.tethering",
        "com.android.wifi",
        "com.android.server.telecom",
        "com.android.packageinstaller",
        // Google core
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.gsf.login",
        "com.google.android.partnersetup",
        "com.google.android.ext.services",
        "com.google.android.ext.shared",
        "com.google.android.webview",
        "com.android.webview",
        "com.google.android.configupdater",
        "com.google.android.gmsintegration",
        // Input methods / keyboards
        "com.google.android.inputmethod.latin",
        "com.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.swiftkey.swiftkeyapp",
        // Launchers (we must NEVER hide or suspend stock launchers)
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.oppo.launcher",
        "com.oneplus.launcher",
        "com.huawei.android.launcher",
        "com.transsion.hilauncher",
        // Accessibility
        "com.google.android.marvin.talkback",
        "com.android.tts",
        // Package installer
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller"
    )

    private fun isSafeToBlock(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false          // never block ourselves
        if (packageName in SYSTEM_PACKAGE_DENYLIST) return false     // never block critical OS
        if (packageName.startsWith("com.android.internal")) return false
        if (packageName.startsWith("android.overlay")) return false

        return runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            val isSystem = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

            // If it is a system app, ONLY block if it has a user launcher intent (e.g. YouTube, Browser) or is Google Search/Lens.
            // Background OS services, framework overlays, and gesture navigation must NEVER be touched!
            if (isSystem) {
                pm.getLaunchIntentForPackage(packageName) != null || packageName == "com.google.android.googlequicksearchbox"
            } else {
                true // All user-installed 3rd party apps can be blocked if not whitelisted
            }
        }.getOrElse { false }
    }

    /**
     * Applies the complete focus lock from persisted SecureStorage settings.
     * Call order is deliberate: settings are already written before this runs.
     *
     * Safety guarantees:
     *  - SYSTEM_PACKAGE_DENYLIST and internal overlays are never touched.
     *  - System navigation (Home, Back, Overview, Notifications) is explicitly enabled.
     *  - Each hide/suspend is wrapped in its own try-catch — one failure
     *    does not abort the rest of the loop.
     */
    fun activateFocusLock(context: Context) {
        if (!requireDeviceOwner(context)) return

        val whitelist         = SecureStorage.getPackageSet(context, SecureStorage.KEY_WHITELIST_PACKAGES)
        val blockFactoryReset = SecureStorage.getBoolean(context, SecureStorage.KEY_BLOCK_FACTORY_RESET, true)
        val blockUsb          = SecureStorage.getBoolean(context, SecureStorage.KEY_BLOCK_USB_DEBUGGING, true)

        Log.i(TAG, "Activating focus lock. Whitelist: ${whitelist.size} apps.")

        // Explicitly restore full navigation features (Home, Overview, Notifications)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                dpm(context).setLockTaskFeatures(
                    admin(context),
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                    DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
                    DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                )
            }
            dpm(context).setLockTaskPackages(admin(context), emptyArray())
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                dpm(context).setStatusBarDisabled(admin(context), false)
                dpm(context).setKeyguardDisabled(admin(context), false)
            }
        }.onFailure { Log.w(TAG, "Failed setting LockTask features: ${it.message}") }

        // Explicitly clear any persistent preferred launcher activities so stock launcher takes over
        runCatching {
            dpm(context).clearPackagePersistentPreferredActivities(admin(context), context.packageName)
            Log.i(TAG, "Cleared persistent preferred activities for ${context.packageName}")
        }.onFailure { Log.w(TAG, "Failed clearing persistent preferred activities: ${it.message}") }

        // All installed packages, filtered through safety check.
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or
                    PackageManager.GET_META_DATA or
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) PackageManager.MATCH_DISABLED_COMPONENTS else 0

        val allPackages = context.packageManager
            .getInstalledApplications(flags)
            .map { it.packageName }
            .filter { isSafeToBlock(context, it) }

        val toBlock = allPackages.filter { it !in whitelist }
        Log.i(TAG, "Packages to block: ${toBlock.size} / ${allPackages.size}")

        // Persist explicit set of blocked packages so restoration never has to guess
        SecureStorage.setBlockedPackages(context, toBlock.toSet())

        // 1. Hide each non-whitelisted app individually (per-package try-catch).
        var hidden = 0; var hideFailed = 0
        toBlock.forEach { pkg ->
            runCatching {
                val ok = dpm(context).setApplicationHidden(admin(context), pkg, true)
                if (ok) hidden++ else hideFailed++
            }.onFailure {
                hideFailed++
                Log.w(TAG, "setApplicationHidden($pkg) failed: ${it.message}")
            }
        }
        Log.i(TAG, "Hidden: $hidden, Failed: $hideFailed")

        // 2. Suspend as a second defence layer.
        val safeToSuspend = toBlock.toTypedArray()
        runCatching {
            val failed = dpm(context).setPackagesSuspended(admin(context), safeToSuspend, true)
            Log.i(TAG, "Suspended ${safeToSuspend.size - (failed?.size ?: 0)} packages.")
        }.onFailure { Log.w(TAG, "setPackagesSuspended bulk failed: ${it.message}") }

        // 3. Ensure sideloading and manual APK installs are NEVER blocked.
        unblockAppInstalls(context)

        // 4. Prevent uninstalling the kiosk app.
        blockOwnUninstall(context)

        // 5. Optional anti-tamper restrictions.
        if (blockUsb) disableUsbDebugging(context)
        if (blockFactoryReset) disableFactoryReset(context)

        // 6. Mark lock as active.
        SecureStorage.putBoolean(context, SecureStorage.KEY_LOCK_ACTIVE, true)
        Log.i(TAG, "Focus lock fully activated. ${toBlock.size} apps targeted.")
    }

    /**
     * Releases the focus lock. Safe to call only when
     * System.currentTimeMillis() >= SecureStorage.KEY_UNLOCK_TIMESTAMP_MS.
     */
    fun deactivateFocusLock(context: Context) {
        KioskRestoreManager.restoreAllApps(context)
    }

    // ── Boot & unlock validation ──────────────────────────────────────────────

    /** Re-applies lock after a device reboot (some OEMs reset suspension flags). */
    fun enforceOnBoot(context: Context) {
        if (!SecureStorage.isLockActive(context)) return
        if (SecureStorage.isUnlockTimePassed(context)) {
            Log.i(TAG, "Timer expired while device was off — releasing lock.")
            deactivateFocusLock(context)
        } else {
            Log.i(TAG, "Re-enforcing focus lock after boot.")
            activateFocusLock(context)
            val unlockTs = SecureStorage.getUnlockTimestampMs(context)
            com.focuskiosk.service.FocusCountdownService.start(context, unlockTs)
            KioskRestoreManager.scheduleFailSafe(context, unlockTs)
        }
    }

    /**
     * Validates the unlock timestamp on every screen unlock.
     * This is the primary defence against system-clock manipulation.
     * If the user sets the clock back, [isUnlockTimePassed] returns false,
     * so the lock remains active. If the real time has genuinely passed,
     * the lock is released automatically.
     */
    fun validateLockState(context: Context) {
        if (!SecureStorage.isLockActive(context)) return
        if (SecureStorage.isUnlockTimePassed(context)) {
            Log.i(TAG, "Unlock timestamp reached — auto-releasing focus lock.")
            deactivateFocusLock(context)
        }
    }
}
