package com.focuskiosk.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.NumberPicker
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.focuskiosk.databinding.ActivitySetupWizardBinding
import com.focuskiosk.launcher.AppInfo
import com.focuskiosk.launcher.HomeLauncherActivity
import com.focuskiosk.policy.PolicyEnforcer
import com.focuskiosk.storage.SecureStorage
import com.focuskiosk.updater.MediaPurgeWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SetupWizardActivity
 * -------------------
 * First-run onboarding. Three steps:
 *
 * 1. Display all installed launchable apps. User checks the ones to KEEP.
 * 2. User picks lock duration via NumberPicker wheels (Years/Days/Hours/Minutes).
 * 3. On confirmation, all settings are persisted and DPM policies are applied.
 *
 * Layout uses ConstraintLayout with RecyclerView height=0dp, constrained
 * between header and bottom controls panel — no nested ScrollView conflicts.
 */
class SetupWizardActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupWizardBinding
    private lateinit var selectAdapter: AppSelectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!PolicyEnforcer.isDeviceOwner(this)) { showOwnerError(); return }

        setupList()
        setupDurationPickers()
        binding.btnActivateLock.setOnClickListener { confirmActivation() }
        loadApps()

        // Schedule the media purge worker (silent, background).
        MediaPurgeWorker.schedule(this)
    }

    // --  App list  ------------------------------------------------------------

    private fun setupList() {
        selectAdapter = AppSelectAdapter()
        // LinearLayoutManager with smooth scroll; nestedScrollingEnabled false
        // prevents the RecyclerView fighting with ConstraintLayout parent.
        val lm = LinearLayoutManager(this)
        binding.rvSelectApps.layoutManager = lm
        binding.rvSelectApps.adapter = selectAdapter
        binding.rvSelectApps.setHasFixedSize(false)
        binding.rvSelectApps.isNestedScrollingEnabled = true
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.packageName != packageName }
                    .mapNotNull { info ->
                        pm.getLaunchIntentForPackage(info.packageName)
                            ?: return@mapNotNull null
                        runCatching {
                            AppInfo(
                                packageName = info.packageName,
                                label       = pm.getApplicationLabel(info).toString(),
                                icon        = pm.getApplicationIcon(info)
                            )
                        }.getOrNull()
                    }
                    .sortedBy { it.label }
            }
            selectAdapter.submitList(apps)
        }
    }

    // --  Duration NumberPickers  ---------------------------------------------

    /**
     * Configures the four NumberPicker widgets.
     *
     * Ranges:
     *   Years   0-5    (0 means none selected)
     *   Days    0-364
     *   Hours   0-23
     *   Minutes 0-59
     *
     * On any value change, the duration-preview label is updated to show
     * the aggregate human-readable total.
     */
    private fun setupDurationPickers() {
        with(binding.pickerYears) {
            minValue = 0; maxValue = 5; value = 0
            wrapSelectorWheel = false
        }
        with(binding.pickerDays) {
            minValue = 0; maxValue = 364; value = 0
            wrapSelectorWheel = false
        }
        with(binding.pickerHours) {
            minValue = 0; maxValue = 23; value = 0
            wrapSelectorWheel = true
        }
        with(binding.pickerMinutes) {
            minValue = 0; maxValue = 59; value = 0
            wrapSelectorWheel = true
        }

        val listener = NumberPicker.OnValueChangeListener { _, _, _ -> updatePreview() }
        binding.pickerYears.setOnValueChangedListener(listener)
        binding.pickerDays.setOnValueChangedListener(listener)
        binding.pickerHours.setOnValueChangedListener(listener)
        binding.pickerMinutes.setOnValueChangedListener(listener)

        updatePreview()
    }

    /**
     * Calculates total duration in milliseconds from the four pickers.
     *
     * unlockTimestamp = currentTimeMillis + selectedDurationMillis
     *
     * Uses calendar-accurate calculation:
     *   years  × 365.25 days  (accounts for leap years on average)
     *   days   × 86400 s
     *   hours  × 3600 s
     *   minutes× 60 s
     */
    private fun selectedDurationMs(): Long {
        val y = binding.pickerYears.value.toLong()
        val d = binding.pickerDays.value.toLong()
        val h = binding.pickerHours.value.toLong()
        val m = binding.pickerMinutes.value.toLong()
        return (y * 365L * 24 * 60 * 60 * 1000) +
               (d * 24L * 60 * 60 * 1000) +
               (h * 60L * 60 * 1000) +
               (m * 60L * 1000)
    }

    private fun updatePreview() {
        val ms = selectedDurationMs()
        binding.tvDurationPreview.text = if (ms <= 0L) {
            "No duration selected"
        } else {
            val days  = ms / (86400_000L)
            val hours = (ms % 86400_000L) / 3600_000L
            val mins  = (ms % 3600_000L) / 60_000L
            "Total: ${days}d ${hours}h ${mins}m"
        }
    }

    // --  Confirm & apply  ----------------------------------------------------

    private fun confirmActivation() {
        val durationMs = selectedDurationMs()
        if (durationMs <= 0L) {
            Toast.makeText(this, "Please select a lock duration.", Toast.LENGTH_LONG).show()
            return
        }

        val whitelist = selectAdapter.getSelectedPackages()
        if (whitelist.isEmpty()) {
            Toast.makeText(this, "Please select at least one app to keep accessible.", Toast.LENGTH_LONG).show()
            return
        }

        val unlockTs = System.currentTimeMillis() + durationMs
        val previewText = binding.tvDurationPreview.text

        AlertDialog.Builder(this)
            .setTitle("Confirm Focus Lock")
            .setMessage(
                "You are about to lock this device.\n" +
                "$previewText\n\n" +
                "${whitelist.size} apps will remain accessible.\n" +
                "All other apps will be hidden and suspended.\n" +
                "This CANNOT be undone before the timer expires.\n\n" +
                "Are you absolutely sure?"
            )
            .setPositiveButton("Lock Now") { _, _ -> applyLock(whitelist, unlockTs) }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
    }

    private fun applyLock(whitelist: Set<String>, unlockTs: Long) {
        val finalWhitelist = whitelist + packageName

        binding.btnActivateLock.isEnabled = false
        Toast.makeText(this, "Activating Focus Lock...", Toast.LENGTH_SHORT).show()

        // 1. Persist settings
        SecureStorage.putPackageSet(this, SecureStorage.KEY_WHITELIST_PACKAGES, finalWhitelist)
        SecureStorage.putLong(this, SecureStorage.KEY_UNLOCK_TIMESTAMP_MS, unlockTs)
        SecureStorage.putBoolean(this, SecureStorage.KEY_BLOCK_FACTORY_RESET,
            binding.switchFactoryReset.isChecked)
        SecureStorage.putBoolean(this, SecureStorage.KEY_BLOCK_USB_DEBUGGING,
            binding.switchUsbDebugging.isChecked)

        // 2. Safe background execution
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                PolicyEnforcer.activateFocusLock(this@SetupWizardActivity)
                // Start sticky foreground countdown service
                com.focuskiosk.service.FocusCountdownService.start(this@SetupWizardActivity, unlockTs)
                // Schedule dual fail-safe (AlarmManager + WorkManager) for exact unlock restoration
                com.focuskiosk.policy.KioskRestoreManager.scheduleFailSafe(this@SetupWizardActivity, unlockTs)
            } catch (e: Exception) {
                Log.e("SetupWizard", "Error activating focus lock", e)
            }

            withContext(Dispatchers.Main) {
                SecureStorage.setSetupCompleted(this@SetupWizardActivity, true)

                // Self-hide: disable SetupWizardActivity component so the app vanishes from the stock launcher & app drawer
                try {
                    packageManager.setComponentEnabledSetting(
                        android.content.ComponentName(this@SetupWizardActivity, SetupWizardActivity::class.java),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    Log.i("SetupWizard", "SetupWizardActivity disabled. FocusKiosk is now hidden in stealth mode.")
                } catch (e: Exception) {
                    Log.e("SetupWizard", "Failed to disable component", e)
                }

                // Return user to the native stock home launcher
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
                finishAffinity()
            }
        }
    }

    // --  Error  ---------------------------------------------------------------

    private fun showOwnerError() {
        AlertDialog.Builder(this)
            .setTitle("Device Owner Required")
            .setMessage(
                "Run this ADB command after factory reset:\n\n" +
                "adb shell dpm set-device-owner \\\n" +
                "  ${packageName}/.admin.FocusDeviceAdminReceiver"
            )
            .setPositiveButton("OK") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}
