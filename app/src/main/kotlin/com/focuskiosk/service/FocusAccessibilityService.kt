package com.focuskiosk.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * FocusAccessibilityService
 * ─────────────────────────
 * Real-time guard intercepting unauthorized in-app web browsing and social media escapes.
 * Specifically targets Messenger's internal BrowserLiteActivity and WebView windows
 * when users click shared Facebook Reels or links, instantly dismissing the browser
 * and returning the user to the chat within 50ms.
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
