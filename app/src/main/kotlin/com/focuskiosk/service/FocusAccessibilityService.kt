package com.focuskiosk.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.focuskiosk.policy.PolicyEnforcer
import com.focuskiosk.storage.SecureStorage

/**
 * FocusAccessibilityService
 * ─────────────────────────
 * Real-time guard intercepting unauthorized in-app web browsing, social media escapes,
 * and launches of blocked applications via notifications or deep links.
 */
@Suppress("DEPRECATION")
class FocusAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FocusAccessibility"
        private var lastActionTimestamp = 0L

        private val PROHIBITED_DOMAINS = listOf(
            "facebook.com",
            "m.facebook.com",
            "touch.facebook.com",
            "web.facebook.com",
            "fb.watch",
            "fb.com",
            "fb.me",
            "instagram.com",
            "tiktok.com",
            "pornhub.com",
            "xvideos.com",
            "xnxx.com",
            "xhamster.com",
            "redtube.com",
            "youporn.com",
            "onlyfans.com"
        )

        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.google.android.apps.chrome",
            "com.transsion.phoenix",
            "com.sec.android.app.sbrowser",
            "org.mozilla.firefox",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.mi.globalbrowser",
            "com.coloros.browser",
            "com.heytap.browser",
            "com.vivo.browser"
        )

        fun isEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${FocusAccessibilityService::class.java.name}"
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(expectedServiceName) || enabledServices.contains(FocusAccessibilityService::class.java.simpleName)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // 0. Financial & Banking App Safe Guard: NexusPay, bKash, Nagad, Cellfin
        // Banking apps in Bangladesh check if any AccessibilityService with canRetrieveWindowContent is active.
        // Automatically disable accessibility so NexusPay launches with zero security warnings!
        val isBankingApp = pkg == "com.dbbl.nexus.pay" || pkg.contains("nexus", ignoreCase = true) ||
                           pkg.contains("dbbl", ignoreCase = true) ||
                           pkg == "com.bKash.customerapp" || pkg == "com.konasl.nagad" ||
                           pkg == "com.islami_bank.cellfin" || pkg == "com.citybank.citytouch" ||
                           pkg == "com.thecitybank.citytouch" || pkg == "com.bracbank.astha"
        if (isBankingApp) {
            Log.i(TAG, "Banking app detected ('$pkg'). Disabling accessibility service immediately to allow secure banking access.")
            mainHandler.post {
                Toast.makeText(
                    applicationContext,
                    "Focus Kiosk: Accessibility disabled for NexusPay security.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            disableSelf()
            return
        }

        // 1. Zero-Tolerance Blocked App Trap: Blocks access via notification taps, deep links, or recents
        if (SecureStorage.getBoolean(applicationContext, SecureStorage.KEY_LOCK_ACTIVE, false)) {
            val blockedPkgs = SecureStorage.getBlockedPackages(applicationContext)
            if (pkg in blockedPkgs) {
                val now = System.currentTimeMillis()
                if (now - lastActionTimestamp > 400L) {
                    lastActionTimestamp = now
                    Log.w(TAG, "BLOCKED APP TRAP: Intercepted unauthorized launch for '$pkg'! Dismissing to Home...")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    runCatching {
                        PolicyEnforcer.suspendPackages(applicationContext, arrayOf(pkg))
                        PolicyEnforcer.hideApplication(applicationContext, pkg)
                    }
                    FocusNotificationService.clearBlockedNotifications(applicationContext)
                    mainHandler.post {
                        Toast.makeText(
                            applicationContext,
                            "Focus Kiosk: Notifications & access for this app are blocked during Focus Mode.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return
            }
        }

        val isSocialApp = pkg == "com.facebook.orca" || pkg == "com.facebook.katana" ||
                          pkg == "com.facebook.lite" || pkg == "com.facebook.mlite" ||
                          pkg == "com.instagram.android"
        val isBrowserApp = pkg in BROWSER_PACKAGES

        if (isSocialApp || isBrowserApp) {
            val isBrowserActivity = className.contains("BrowserLiteActivity", ignoreCase = true) ||
                                    className.contains("BrowserLite", ignoreCase = true) ||
                                    className.contains("InAppBrowser", ignoreCase = true) ||
                                    className.contains("ChromeTab", ignoreCase = true) ||
                                    className.contains("CustomTab", ignoreCase = true) ||
                                    isBrowserApp

            if (isBrowserActivity || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                checkAndDismissIfProhibited(pkg, className)
            }
        }
    }

    private fun checkAndDismissIfProhibited(pkg: String, className: String) {
        val rootNode = rootInActiveWindow ?: return
        try {
            var matchedDomain: String? = null

            // 1. Direct search by prohibited domain strings
            for (domain in PROHIBITED_DOMAINS) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(domain)
                if (!nodes.isNullOrEmpty()) {
                    matchedDomain = domain
                    nodes.forEach { runCatching { it.recycle() } }
                    break
                }
            }

            // 2. If inside BrowserLiteActivity with Facebook branding (e.g. "Reels" + "Open app")
            if (matchedDomain == null && className.contains("BrowserLite", ignoreCase = true)) {
                val reelsNodes = rootNode.findAccessibilityNodeInfosByText("Reels")
                val openAppNodes = rootNode.findAccessibilityNodeInfosByText("Open app")
                val fbNodes = rootNode.findAccessibilityNodeInfosByText("Facebook")
                if (!reelsNodes.isNullOrEmpty() && (!openAppNodes.isNullOrEmpty() || !fbNodes.isNullOrEmpty())) {
                    matchedDomain = "facebook.com/reel"
                }
                reelsNodes?.forEach { runCatching { it.recycle() } }
                openAppNodes?.forEach { runCatching { it.recycle() } }
                fbNodes?.forEach { runCatching { it.recycle() } }
            }

            if (matchedDomain != null) {
                val now = System.currentTimeMillis()
                if (now - lastActionTimestamp > 900L) {
                    lastActionTimestamp = now
                    Log.w(TAG, "BLOCKED in-app browser escape to '$matchedDomain' in $pkg ($className)!")
                    
                    // Instantly close the in-app browser and return to chat
                    performGlobalAction(GLOBAL_ACTION_BACK)

                    mainHandler.post {
                        Toast.makeText(
                            applicationContext,
                            "Focus Kiosk: Facebook web access is blocked.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } finally {
            runCatching { rootNode.recycle() }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "FocusAccessibilityService interrupted.")
    }
}
