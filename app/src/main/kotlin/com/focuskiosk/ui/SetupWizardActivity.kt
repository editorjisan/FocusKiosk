package com.focuskiosk.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focuskiosk.databinding.ActivitySetupWizardBinding
import com.focuskiosk.launcher.AppInfo
import com.focuskiosk.launcher.HomeLauncherActivity
import com.focuskiosk.policy.KioskRestoreManager
import com.focuskiosk.policy.PolicyEnforcer
import com.focuskiosk.storage.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SetupWizardActivity
 * -------------------
 * First-run onboarding.
 *
 * 1. Display all installed launchable apps. User checks the ones to KEEP.
 * 2. User picks lock duration via modern digital timer card with presets & steppers.
 * 3. On confirmation, all settings are persisted, DPM policies are applied,
 *    and the app self-hides from launcher until timer completion.
 */
class SetupWizardActivity : AppCompatActivity() {

    companion object {
        @Volatile
        private var cachedAppList: List<AppInfo>? = null
    }

    private lateinit var binding: ActivitySetupWizardBinding
    private lateinit var selectAdapter: AppSelectAdapter

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("SetupWizard", "POST_NOTIFICATIONS granted")
        } else {
            Log.w("SetupWizard", "POST_NOTIFICATIONS denied by user")
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If not device owner, show setup instructions and close.
        if (!PolicyEnforcer.isDeviceOwner(this)) {
            showOwnerError()
            return
        }

        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureNotificationPermission()

        binding.tvVersionInfo.text = "v${com.focuskiosk.BuildConfig.VERSION_NAME} (Build ${com.focuskiosk.BuildConfig.VERSION_CODE})"

        // Intercept Back button during active search to dismiss keyboard & restore bottom panel
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.etSearchApps.hasFocus() || binding.tvCancelSearch.visibility == View.VISIBLE || !binding.etSearchApps.text.isNullOrEmpty()) {
                    binding.etSearchApps.text?.clear()
                    setSearchModeActive(false)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Observe soft keyboard visibility: hide controlsPanel and headers to give rvSelectApps 100% of space
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible) {
                binding.controlsPanel.visibility = View.GONE
                binding.otaUpdateCard.visibility = View.GONE
                binding.headerTitleLayout.visibility = View.GONE
                binding.tvCancelSearch.visibility = View.VISIBLE
            } else if (!binding.etSearchApps.hasFocus() && binding.etSearchApps.text.isNullOrEmpty()) {
                binding.controlsPanel.visibility = View.VISIBLE
                binding.otaUpdateCard.visibility = View.VISIBLE
                binding.headerTitleLayout.visibility = View.VISIBLE
                binding.tvCancelSearch.visibility = View.GONE
            }
            insets
        }

        setupList()
        setupSearchBar()
        setupDurationControls()
        binding.btnActivateLock.setOnClickListener { confirmActivation() }

        binding.btnCheckUpdate.setOnClickListener { triggerUpdateFlow() }

        // Auto-check for updates 1.5 seconds after launch to ensure smooth zero-touch update
        lifecycleScope.launch {
            delay(1500L)
            triggerUpdateFlow()
        }

        loadApps()

        // Background silent check for updates on setup launch
        com.focuskiosk.updater.SilentUpdateManager.triggerImmediateCheck(this)

        // Ensure sideloading and APK installs are never restricted
        PolicyEnforcer.unblockAppInstalls(this)
        PolicyEnforcer.unrestrictSettingsSilently(this)
    }

    override fun onResume() {
        super.onResume()
        // Ensure sideloading and APK installs are never restricted
        PolicyEnforcer.unblockAppInstalls(this)
        PolicyEnforcer.unrestrictSettingsSilently(this)

        val deviceContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext()
        } else {
            this
        }
        val sp = deviceContext.getSharedPreferences("focus_kiosk_prefs", android.content.Context.MODE_PRIVATE)
        val epochTime = sp.getLong("unlock_epoch_time", 0L)
        val secureTs = runCatching { SecureStorage.getUnlockTimestampMs(this) }.getOrDefault(0L)
        val unlockEpoch = if (epochTime > 0L) epochTime else secureTs
        val isLocked = sp.getBoolean("lock_active", false) || runCatching { SecureStorage.isLockActive(this) }.getOrDefault(false)

        if (isLocked && unlockEpoch in 1..System.currentTimeMillis()) {
            Log.i("SetupWizard", "Unlock timestamp reached on resume ($unlockEpoch <= ${System.currentTimeMillis()}) — restoring apps immediately!")
            KioskRestoreManager.restoreAllApps(this)
            loadApps()
        }
    }

    private fun triggerUpdateFlow() {
        binding.btnCheckUpdate.isEnabled = false
        binding.progressBarUpdate.visibility = android.view.View.VISIBLE
        binding.progressBarUpdate.progress = 0
        binding.tvUpdateStatus.text = "Checking for updates..."

        lifecycleScope.launch {
            com.focuskiosk.updater.SilentUpdateManager.checkAndInstallUpdateWithProgress(this@SetupWizardActivity) { percent, statusText ->
                binding.progressBarUpdate.progress = percent
                binding.tvUpdateStatus.text = statusText
                if (percent >= 100 || statusText.startsWith("Already") || statusText.startsWith("Failed") || statusText.startsWith("Error")) {
                    binding.btnCheckUpdate.isEnabled = true
                }
            }
        }
    }

    // --  App list  ------------------------------------------------------------

    private fun setupList() {
        selectAdapter = AppSelectAdapter()
        val lm = LinearLayoutManager(this)
        binding.rvSelectApps.layoutManager = lm
        binding.rvSelectApps.adapter = selectAdapter
        binding.rvSelectApps.setHasFixedSize(false)
        binding.rvSelectApps.isNestedScrollingEnabled = true

        // Dismiss keyboard when dragging/scrolling results
        binding.rvSelectApps.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(binding.etSearchApps.windowToken, 0)
                    binding.etSearchApps.clearFocus()
                    if (binding.etSearchApps.text.isNullOrEmpty()) {
                        setSearchModeActive(false)
                    } else {
                        binding.controlsPanel.visibility = View.VISIBLE
                        binding.otaUpdateCard.visibility = View.VISIBLE
                        binding.headerTitleLayout.visibility = View.VISIBLE
                        binding.tvCancelSearch.visibility = View.GONE
                    }
                }
            }
        })
    }

    private fun setSearchModeActive(active: Boolean) {
        if (active) {
            binding.controlsPanel.visibility = View.GONE
            binding.otaUpdateCard.visibility = View.GONE
            binding.headerTitleLayout.visibility = View.GONE
            binding.tvCancelSearch.visibility = View.VISIBLE
        } else {
            binding.controlsPanel.visibility = View.VISIBLE
            binding.otaUpdateCard.visibility = View.VISIBLE
            binding.headerTitleLayout.visibility = View.VISIBLE
            binding.tvCancelSearch.visibility = View.GONE
            binding.etSearchApps.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(binding.etSearchApps.windowToken, 0)
        }
    }

    private fun setupSearchBar() {
        binding.etSearchApps.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                setSearchModeActive(true)
            }
        }

        binding.etSearchApps.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString() ?: ""
                selectAdapter.filter(q)
                binding.btnClearSearch.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                binding.tvNoAppsFound.visibility = if (selectAdapter.itemCount == 0) View.VISIBLE else View.GONE
                if (q.isNotEmpty()) {
                    setSearchModeActive(true)
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.tvCancelSearch.setOnClickListener {
            binding.etSearchApps.text?.clear()
            setSearchModeActive(false)
        }

        binding.btnClearSearch.setOnClickListener {
            binding.etSearchApps.text?.clear()
        }

        binding.etSearchApps.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(binding.etSearchApps.windowToken, 0)
                binding.etSearchApps.clearFocus()
                if (binding.etSearchApps.text.isNullOrEmpty()) {
                    setSearchModeActive(false)
                } else {
                    binding.controlsPanel.visibility = View.VISIBLE
                    binding.otaUpdateCard.visibility = View.VISIBLE
                    binding.headerTitleLayout.visibility = View.VISIBLE
                    binding.tvCancelSearch.visibility = View.GONE
                }
                true
            } else {
                false
            }
        }
    }

    private fun loadApps() {
        // 1. Instant cache hit: render app list in 0ms without waiting for icon decoding
        cachedAppList?.let { cached ->
            selectAdapter.submitList(cached)
        }

        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                if (cachedAppList != null) {
                    return@withContext cachedAppList!!
                }

                val pm = packageManager

                // Query all launchable apps directly in a single IPC call (10x faster)
                val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = runCatching {
                    pm.queryIntentActivities(launchIntent, 0)
                }.getOrElse { emptyList() }

                val appList = ArrayList<AppInfo>(resolveInfos.size + 4)
                val seenPackages = HashSet<String>(resolveInfos.size + 4)

                for (resolve in resolveInfos) {
                    val pkg = resolve.activityInfo?.packageName ?: continue
                    if (pkg == packageName || seenPackages.contains(pkg)) continue

                    runCatching {
                        val label = resolve.loadLabel(pm).toString()
                        val icon = resolve.loadIcon(pm)
                        appList.add(AppInfo(packageName = pkg, label = label, icon = icon))
                        seenPackages.add(pkg)
                    }
                }

                // Explicit guarantee: ensure Google App / QuickSearchBox is indexed if present
                if (!seenPackages.contains("com.google.android.googlequicksearchbox")) {
                    runCatching {
                        val googleInfo = pm.getApplicationInfo("com.google.android.googlequicksearchbox", 0)
                        appList.add(
                            AppInfo(
                                packageName = googleInfo.packageName,
                                label = pm.getApplicationLabel(googleInfo).toString(),
                                icon = pm.getApplicationIcon(googleInfo)
                            )
                        )
                        seenPackages.add(googleInfo.packageName)
                    }
                }

                appList.sortBy { it.label.lowercase() }
                cachedAppList = appList
                appList
            }
            selectAdapter.submitList(apps)
        }
    }

    // --  Duration Controls (Steppers & Presets)  -----------------------------

    private var selectedDays: Int = 0
    private var selectedHours: Int = 0
    private var selectedMinutes: Int = 1 // Default 1 min for instant usability

    private fun setupDurationControls() {
        // Stepper buttons for Days
        binding.btnPlusDay.setOnClickListener {
            if (selectedDays < 365) selectedDays++
            updateDurationDisplay()
        }
        binding.btnMinusDay.setOnClickListener {
            if (selectedDays > 0) selectedDays--
            updateDurationDisplay()
        }

        // Stepper buttons for Hours
        binding.btnPlusHour.setOnClickListener {
            if (selectedHours < 23) {
                selectedHours++
            } else {
                selectedHours = 0
                if (selectedDays < 365) selectedDays++
            }
            updateDurationDisplay()
        }
        binding.btnMinusHour.setOnClickListener {
            if (selectedHours > 0) {
                selectedHours--
            } else if (selectedDays > 0) {
                selectedDays--
                selectedHours = 23
            }
            updateDurationDisplay()
        }

        // Stepper buttons for Minutes
        binding.btnPlusMinute.setOnClickListener {
            if (selectedMinutes < 59) {
                selectedMinutes++
            } else {
                selectedMinutes = 0
                if (selectedHours < 23) {
                    selectedHours++
                } else {
                    selectedHours = 0
                    if (selectedDays < 365) selectedDays++
                }
            }
            updateDurationDisplay()
        }
        binding.btnMinusMinute.setOnClickListener {
            if (selectedMinutes > 0) {
                selectedMinutes--
            } else if (selectedHours > 0) {
                selectedHours--
                selectedMinutes = 59
            } else if (selectedDays > 0) {
                selectedDays--
                selectedHours = 23
                selectedMinutes = 59
            }
            updateDurationDisplay()
        }

        // Quick Presets
        binding.chipPreset1m.setOnClickListener { setPreset(0, 0, 1, binding.chipPreset1m) }
        binding.chipPreset2m.setOnClickListener { setPreset(0, 0, 2, binding.chipPreset2m) }
        binding.chipPreset5m.setOnClickListener { setPreset(0, 0, 5, binding.chipPreset5m) }
        binding.chipPreset15m.setOnClickListener { setPreset(0, 0, 15, binding.chipPreset15m) }
        binding.chipPreset30m.setOnClickListener { setPreset(0, 0, 30, binding.chipPreset30m) }
        binding.chipPreset1h.setOnClickListener { setPreset(0, 1, 0, binding.chipPreset1h) }
        binding.chipPreset2h.setOnClickListener { setPreset(0, 2, 0, binding.chipPreset2h) }
        binding.chipPreset4h.setOnClickListener { setPreset(0, 4, 0, binding.chipPreset4h) }
        binding.chipPreset1d.setOnClickListener { setPreset(1, 0, 0, binding.chipPreset1d) }
        binding.chipPreset7d.setOnClickListener { setPreset(7, 0, 0, binding.chipPreset7d) }
        binding.chipPreset30d.setOnClickListener { setPreset(30, 0, 0, binding.chipPreset30d) }

        setPreset(0, 0, 1, binding.chipPreset1m)
    }

    private fun setPreset(days: Int, hours: Int, minutes: Int, activeBtn: com.google.android.material.button.MaterialButton? = null) {
        selectedDays = days
        selectedHours = hours
        selectedMinutes = minutes
        updateDurationDisplay()

        val allChips = listOf(
            binding.chipPreset1m, binding.chipPreset2m, binding.chipPreset5m,
            binding.chipPreset15m, binding.chipPreset30m, binding.chipPreset1h,
            binding.chipPreset2h, binding.chipPreset4h, binding.chipPreset1d,
            binding.chipPreset7d, binding.chipPreset30d
        )
        val selectedColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#007AFF"))
        val defaultColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2C2C2E"))
        for (chip in allChips) {
            chip.backgroundTintList = if (chip == activeBtn) selectedColor else defaultColor
        }
    }

    private fun updateDurationDisplay() {
        binding.tvDisplayDays.text = String.format(Locale.US, "%02d", selectedDays)
        binding.tvDisplayHours.text = String.format(Locale.US, "%02d", selectedHours)
        binding.tvDisplayMinutes.text = String.format(Locale.US, "%02d", selectedMinutes)

        val totalMs = selectedDurationMs()
        if (totalMs <= 0L) {
            binding.tvDurationPreview.text = "0m"
            binding.tvUnlockTimestampPreview.text = "Please select a duration"
            binding.btnActivateLock.isEnabled = false
        } else {
            binding.btnActivateLock.isEnabled = true
            val durationParts = mutableListOf<String>()
            if (selectedDays > 0) durationParts.add("${selectedDays}d")
            if (selectedHours > 0) durationParts.add("${selectedHours}h")
            if (selectedMinutes > 0) durationParts.add("${selectedMinutes}m")
            val durationString = durationParts.joinToString(" ")
            binding.tvDurationPreview.text = durationString

            val unlockTime = System.currentTimeMillis() + totalMs
            val timeFormat = SimpleDateFormat("EEE, MMM d  h:mm a", Locale.getDefault())
            binding.tvUnlockTimestampPreview.text = "Unlocks at: ${timeFormat.format(Date(unlockTime))}"
        }
    }

    private fun selectedDurationMs(): Long {
        val totalMinutes = (selectedDays * 24L * 60L) + (selectedHours * 60L) + selectedMinutes
        return totalMinutes * 60_000L
    }

    // --  Confirm & apply  ----------------------------------------------------

    private fun confirmActivation() {
        ensureNotificationPermission()
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
        val previewText = binding.tvDurationPreview.text.toString()

        if (!com.focuskiosk.service.FocusAccessibilityService.isEnabled(this)) {
            showAppleEscapeGuardDialog(whitelist, unlockTs, previewText)
            return
        }

        showAppleLockConfirmDialog(whitelist, unlockTs, previewText)
    }

    private fun showAppleEscapeGuardDialog(whitelist: Set<String>, unlockTs: Long, previewText: String) {
        val dialog = Dialog(this)
        val dialogBinding = com.focuskiosk.databinding.DialogIosEscapeGuardBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        // Step 1: Open App Info for 3 dots -> Allow restricted settings
        dialogBinding.btnOpenAppInfo.setOnClickListener {
            runCatching {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }.onFailure {
                Toast.makeText(this, "Could not open App Info", Toast.LENGTH_SHORT).show()
            }
        }

        // Step 2: Open Accessibility Settings
        dialogBinding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            dialog.dismiss()
        }

        // Action 3: Continue Without Guard
        dialogBinding.btnContinueAnyway.setOnClickListener {
            dialog.dismiss()
            showAppleLockConfirmDialog(whitelist, unlockTs, previewText)
        }

        dialog.show()
    }

    private fun showAppleLockConfirmDialog(whitelist: Set<String>, unlockTs: Long, previewText: String) {
        val dialog = Dialog(this)
        val dialogBinding = com.focuskiosk.databinding.DialogIosConfirmLockBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.setCancelable(false)

        dialogBinding.tvConfirmDuration.text = previewText
        dialogBinding.tvConfirmWhitelistCount.text = "${whitelist.size} apps will remain accessible"

        dialogBinding.btnConfirmLockNow.setOnClickListener {
            dialog.dismiss()
            applyLock(whitelist, unlockTs)
        }

        dialogBinding.btnConfirmCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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

        // Save directly to unencrypted SharedPreferences for instant access by Watchdog & AlarmManager
        getSharedPreferences("focus_kiosk_prefs", android.content.Context.MODE_PRIVATE).edit()
            .putLong("unlock_epoch_time", unlockTs)
            .putBoolean("lock_active", true)
            .apply()

        // Also save to DirectBoot Device Protected Storage so BootReceiver can access before screen unlock
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            runCatching {
                createDeviceProtectedStorageContext()
                    .getSharedPreferences("focus_kiosk_prefs", android.content.Context.MODE_PRIVATE).edit()
                    .putLong("unlock_epoch_time", unlockTs)
                    .putBoolean("lock_active", true)
                    .apply()
            }
        }

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

                // Self-hide FocusKiosk itself from launcher and app drawer during lock
                try {
                    packageManager.setComponentEnabledSetting(
                        android.content.ComponentName(this@SetupWizardActivity, SetupWizardActivity::class.java),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    Log.i("SetupWizard", "SetupWizardActivity disabled to self-hide during lock.")
                } catch (e: Exception) {
                    Log.e("SetupWizard", "Failed to disable component state", e)
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
