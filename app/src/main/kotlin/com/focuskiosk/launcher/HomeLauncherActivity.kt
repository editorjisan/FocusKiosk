package com.focuskiosk.launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.focuskiosk.databinding.ActivityHomeLauncherBinding
import com.focuskiosk.policy.PolicyEnforcer
import com.focuskiosk.storage.SecureStorage
import com.focuskiosk.ui.SetupWizardActivity
import com.focuskiosk.util.DurationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HomeLauncherActivity
 * ────────────────────
 * The device home screen replacement.
 *
 * Declared with HOME + DEFAULT intent-filter categories so the OS
 * presents it as the default home app once Device Owner is set.
 *
 * Behaviour:
 *  - First launch (setup incomplete)  → redirects to SetupWizardActivity.
 *  - Active lock                      → shows whitelisted app icons + sleek countdown.
 *  - onResume                         → re-validates expiry timestamp (clock-tamper defence).
 *  - onNewIntent                      → smoothly returns home when Home gesture/button is pressed.
 *  - Async icon loading               → icons decoded off main thread via Coroutines.
 */
class HomeLauncherActivity : AppCompatActivity() {

    companion object {
        private const val TAG             = "HomeLauncher"
        private const val TICKER_INTERVAL = 1_000L // real-time countdown every second
    }

    private lateinit var binding: ActivityHomeLauncherBinding
    private lateinit var adapter: AppGridAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val ticker  = object : Runnable {
        override fun run() {
            updateCountdown()
            handler.postDelayed(this, TICKER_INTERVAL)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Clean modern back press handling: at the root launcher, back is a no-op
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "Back pressed at home root — no-op.")
            }
        })

        // Redirect to setup wizard on first launch.
        if (!SecureStorage.isSetupCompleted(this)) {
            startActivity(Intent(this, SetupWizardActivity::class.java))
            return // Do not finish() — stay as the home stack root.
        }

        setupGrid()
        loadWhitelistedApps()
    }

    override fun onResume() {
        super.onResume()
        if (SecureStorage.isSetupCompleted(this)) {
            if (!::adapter.isInitialized) {
                setupGrid()
            }
            loadWhitelistedApps()
        }
        // Re-validate timer on every resume to catch clock-rollback attacks.
        PolicyEnforcer.validateLockState(this)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateCountdown()
        if (SecureStorage.isSetupCompleted(this)) {
            if (!::adapter.isInitialized) {
                setupGrid()
            }
            loadWhitelistedApps()
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun setupGrid() {
        adapter = AppGridAdapter { app ->
            val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
                // Ensure proper task reset so pressing Home returns to launcher
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Log.w(TAG, "No launch intent for ${app.packageName}")
            }
        }
        binding.rvApps.layoutManager = GridLayoutManager(this, 4)
        binding.rvApps.adapter = adapter
    }

    /**
     * Resolves each whitelist package to its current icon and label asynchronously
     * using Kotlin Coroutines on Dispatchers.IO to eliminate any UI stutter.
     */
    private fun loadWhitelistedApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val whitelist = SecureStorage.getPackageSet(this@HomeLauncherActivity, SecureStorage.KEY_WHITELIST_PACKAGES)
                val pm = packageManager
                whitelist.mapNotNull { pkg ->
                    runCatching {
                        val info = pm.getApplicationInfo(pkg, 0)
                        AppInfo(
                            packageName = pkg,
                            label       = pm.getApplicationLabel(info).toString(),
                            icon        = pm.getApplicationIcon(info)
                        )
                    }.getOrNull()
                }.sortedBy { it.label.lowercase() }
            }
            adapter.submitList(apps)
            Log.i(TAG, "Launcher ready: ${apps.size} whitelisted apps loaded asynchronously.")
        }
    }

    private fun updateCountdown() {
        val unlockMs  = SecureStorage.getUnlockTimestampMs(this)
        val remaining = unlockMs - System.currentTimeMillis()
        binding.tvCountdown.text = if (remaining > 0)
            DurationParser.formatRemaining(remaining)
        else
            "EXPIRED"
    }
}
