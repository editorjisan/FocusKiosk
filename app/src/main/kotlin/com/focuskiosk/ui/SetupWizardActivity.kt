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
open class SetupWizardActivity : AppCompatActivity() {

    companion object {
        @Volatile
        private var cachedAppList: List<AppInfo>? = null
    }

    private lateinit var binding: ActivitySetupWizardBinding
    private lateinit var selectAdapter: AppSelectAdapter

    private var selectedIntervalDays: Int = 1
    private var selectedWorkWindowStartHour: Int = 20
    private var selectedWorkWindowStartMinute: Int = 0
    private var selectedWorkWindowDurationMins: Int = 60
    private var activeCountdownJob: kotlinx.coroutines.Job? = null

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

        // Force-enable all launcher components to recover from any previous disabled states
        runCatching {
            val pm = packageManager
            pm.setComponentEnabledSetting(
                android.content.ComponentName(this, "com.focuskiosk.MainActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                android.content.ComponentName(this, "com.focuskiosk.ui.SetupWizardActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }

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
        setupWorkWindowControls()
        setupAlarmControls()
        binding.btnActivateLock.setOnClickListener { confirmActivation() }

        binding.btnCheckUpdate.setOnClickListener { triggerUpdateFlow() }

        // Auto-check for updates 1.5 seconds after launch to ensure smooth zero-touch update
        lifecycleScope.launch {
            delay(1500L)
            triggerUpdateFlow()
        }

        loadApps()
        syncLockState()

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

        syncLockState()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeCountdownJob?.cancel()
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

        // Dismiss keyboard and release focus when dragging/scrolling results
        binding.rvSelectApps.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(binding.etSearchApps.windowToken, 0)
                    binding.etSearchApps.clearFocus()
                    binding.root.requestFocus()
                    if (binding.etSearchApps.text.isNullOrEmpty()) {
                        setSearchModeActive(false)
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0 && binding.etSearchApps.hasFocus()) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(binding.etSearchApps.windowToken, 0)
                    binding.etSearchApps.clearFocus()
                    binding.root.requestFocus()
                }
            }
        })

        // Dismiss keyboard and focus root on touch to avoid spurious auto-focus
        binding.rvSelectApps.setOnTouchListener { _, _ ->
            if (binding.etSearchApps.hasFocus()) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(binding.etSearchApps.windowToken, 0)
                binding.etSearchApps.clearFocus()
                binding.root.requestFocus()
                if (binding.etSearchApps.text.isNullOrEmpty()) {
                    setSearchModeActive(false)
                }
            }
            false
        }
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
            binding.root.requestFocus()
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

    // --  Work Window Controls  ------------------------------------------------

    private fun setupWorkWindowControls() {
        selectedIntervalDays = SecureStorage.getInt(this, SecureStorage.KEY_WORK_WINDOW_INTERVAL_DAYS, 1).coerceIn(1, 3)
        selectedWorkWindowStartHour = SecureStorage.getInt(this, SecureStorage.KEY_WORK_WINDOW_START_HOUR, 20)
        selectedWorkWindowStartMinute = SecureStorage.getInt(this, SecureStorage.KEY_WORK_WINDOW_START_MINUTE, 0)
        selectedWorkWindowDurationMins = SecureStorage.getInt(this, SecureStorage.KEY_WORK_WINDOW_DURATION_MINS, 60)
        val isEnabled = SecureStorage.getBoolean(this, SecureStorage.KEY_WORK_WINDOW_ENABLED, true)

        binding.switchWorkWindow.isChecked = isEnabled
        binding.layoutWorkWindowConfig.visibility = if (isEnabled) View.VISIBLE else View.GONE

        binding.switchWorkWindow.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutWorkWindowConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateWorkWindowSummary()
        }

        updateIntervalButtons()

        binding.btnIntervalDaily.setOnClickListener {
            selectedIntervalDays = 1
            updateIntervalButtons()
            updateWorkWindowSummary()
        }
        binding.btnInterval2Days.setOnClickListener {
            selectedIntervalDays = 2
            updateIntervalButtons()
            updateWorkWindowSummary()
        }
        binding.btnInterval3Days.setOnClickListener {
            selectedIntervalDays = 3
            updateIntervalButtons()
            updateWorkWindowSummary()
        }

        binding.btnPickWorkWindowTime.setOnClickListener {
            val tpd = android.app.TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    selectedWorkWindowStartHour = hourOfDay
                    selectedWorkWindowStartMinute = minute
                    updateWorkWindowSummary()
                },
                selectedWorkWindowStartHour,
                selectedWorkWindowStartMinute,
                false
            )
            tpd.show()
        }

        binding.btnPickWorkWindowDuration.setOnClickListener {
            selectedWorkWindowDurationMins = when (selectedWorkWindowDurationMins) {
                30 -> 60
                60 -> 120
                120 -> 180
                else -> 30
            }
            updateWorkWindowSummary()
        }

        updateWorkWindowSummary()
    }

    private fun updateIntervalButtons() {
        val selectedColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#007AFF"))
        val defaultColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2C2C2E"))

        binding.btnIntervalDaily.backgroundTintList = if (selectedIntervalDays == 1) selectedColor else defaultColor
        binding.btnInterval2Days.backgroundTintList = if (selectedIntervalDays == 2) selectedColor else defaultColor
        binding.btnInterval3Days.backgroundTintList = if (selectedIntervalDays == 3) selectedColor else defaultColor
    }

    private fun updateWorkWindowSummary() {
        val amPm = if (selectedWorkWindowStartHour >= 12) "PM" else "AM"
        val hour12 = if (selectedWorkWindowStartHour % 12 == 0) 12 else selectedWorkWindowStartHour % 12
        val timeStr = String.format(Locale.getDefault(), "%d:%02d %s", hour12, selectedWorkWindowStartMinute, amPm)
        binding.btnPickWorkWindowTime.text = "Time: $timeStr"

        val durationStr = if (selectedWorkWindowDurationMins < 60) "${selectedWorkWindowDurationMins}m" else "${selectedWorkWindowDurationMins / 60}h"
        binding.btnPickWorkWindowDuration.text = "Duration: $durationStr"

        val intervalStr = when (selectedIntervalDays) {
            1 -> "Daily"
            2 -> "Every 2 days"
            3 -> "Every 3 days"
            else -> "Every $selectedIntervalDays days"
        }
        val summaryText = "$intervalStr at $timeStr ($durationStr)"
        binding.tvWorkWindowSummary.text = summaryText
    }

    // --  Lock State Sync & Countdown  -----------------------------------------

    private fun syncLockState() {
        val deviceContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext()
        } else {
            this
        }
        val sp = deviceContext.getSharedPreferences("focus_kiosk_prefs", Context.MODE_PRIVATE)
        val epochTime = sp.getLong("unlock_epoch_time", 0L)
        val secureTs = runCatching { SecureStorage.getUnlockTimestampMs(this) }.getOrDefault(0L)
        val unlockEpoch = if (epochTime > 0L) epochTime else secureTs
        val isLocked = sp.getBoolean("lock_active", false) || runCatching { SecureStorage.isLockActive(this) }.getOrDefault(false)

        val now = System.currentTimeMillis()
        if (isLocked && unlockEpoch in 1..now) {
            Log.i("SetupWizard", "Unlock timestamp reached ($unlockEpoch <= $now) — restoring apps immediately!")
            activeCountdownJob?.cancel()
            KioskRestoreManager.restoreAllApps(this)
            showSetupMode()
            loadApps()
        } else if (isLocked && unlockEpoch > now) {
            showActiveLockMode(unlockEpoch)
        } else {
            showSetupMode()
        }
    }

    private fun showActiveLockMode(unlockEpoch: Long) {
        binding.activeLockPanel.visibility = View.VISIBLE
        binding.searchRowLayout.visibility = View.GONE
        binding.rvSelectApps.visibility = View.GONE
        binding.controlsPanel.visibility = View.GONE
        binding.tvNoAppsFound.visibility = View.GONE

        val timeFormat = SimpleDateFormat("EEEE, MMM d 'at' h:mm a", Locale.getDefault())
        binding.tvActiveLockTargetDate.text = "Unlocks: ${timeFormat.format(Date(unlockEpoch))}"

        activeCountdownJob?.cancel()
        activeCountdownJob = lifecycleScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val remainingMs = unlockEpoch - now
                if (remainingMs <= 0L) {
                    binding.tvActiveLockCountdown.text = "00d 00h 00m 00s"
                    delay(500L)
                    KioskRestoreManager.restoreAllApps(this@SetupWizardActivity)
                    showSetupMode()
                    loadApps()
                    break
                }
                val days = remainingMs / (24L * 3600_000L)
                val hours = (remainingMs % (24L * 3600_000L)) / 3600_000L
                val minutes = (remainingMs % 3600_000L) / 60_000L
                val seconds = (remainingMs % 60_000L) / 1000L
                binding.tvActiveLockCountdown.text = String.format(Locale.US, "%02dd %02dh %02dm %02ds", days, hours, minutes, seconds)

                // Live upload window countdown
                updateActiveWorkWindowLiveCountdown(now)

                // Live extreme wake-up alarm countdown
                updateActiveAlarmLiveCountdown(now)

                delay(1000L)
            }
        }
    }

    private fun updateActiveWorkWindowLiveCountdown(now: Long) {
        val isWorkWindowEnabled = SecureStorage.getBoolean(this, SecureStorage.KEY_WORK_WINDOW_ENABLED, false)
        if (!isWorkWindowEnabled) {
            binding.tvActiveWorkWindowStatus.text = "Upload Window: Disabled"
            binding.tvActiveWorkWindowStatus.setTextColor(Color.parseColor("#8E8E93"))
            binding.tvActiveWorkWindowCountdown.visibility = View.GONE
            binding.tvActiveWorkWindowDetails.text = "No periodic upload windows configured."
            return
        }

        binding.tvActiveWorkWindowCountdown.visibility = View.VISIBLE
        val isWindowActive = com.focuskiosk.policy.WorkWindowScheduler.isWindowActive(this)
        val startMs = SecureStorage.getLong(this, SecureStorage.KEY_NEXT_WINDOW_START_MS, 0L)
        val endMs = SecureStorage.getLong(this, SecureStorage.KEY_NEXT_WINDOW_END_MS, 0L)

        if (isWindowActive && endMs > now) {
            val rem = endMs - now
            val hours = rem / 3600_000L
            val minutes = (rem % 3600_000L) / 60_000L
            val seconds = (rem % 60_000L) / 1000L
            binding.tvActiveWorkWindowStatus.text = "Upload Window: Active Now 🟢"
            binding.tvActiveWorkWindowStatus.setTextColor(Color.parseColor("#34C759"))
            binding.tvActiveWorkWindowCountdown.text = String.format(Locale.US, "Auto-locks in: %02dh %02dm %02ds", hours, minutes, seconds)
            binding.tvActiveWorkWindowCountdown.setTextColor(Color.parseColor("#34C759"))
            binding.tvActiveWorkWindowDetails.text = "Creator apps are temporarily accessible for video uploading."
        } else {
            val effectiveStartMs = if (startMs > now) startMs else {
                val sh = SecureStorage.getInt(this, SecureStorage.KEY_WORK_WINDOW_START_HOUR, 20)
                val sm = SecureStorage.getInt(this, SecureStorage.KEY_WORK_WINDOW_START_MINUTE, 0)
                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, sh)
                    set(java.util.Calendar.MINUTE, sm)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                    if (timeInMillis <= now) add(java.util.Calendar.DAY_OF_YEAR, SecureStorage.getInt(this@SetupWizardActivity, SecureStorage.KEY_WORK_WINDOW_INTERVAL_DAYS, 1).coerceAtLeast(1))
                }
                cal.timeInMillis
            }

            val rem = (effectiveStartMs - now).coerceAtLeast(0L)
            val days = rem / (24L * 3600_000L)
            val hours = (rem % (24L * 3600_000L)) / 3600_000L
            val minutes = (rem % 3600_000L) / 60_000L
            val seconds = (rem % 60_000L) / 1000L

            binding.tvActiveWorkWindowStatus.text = "Upload Window: Scheduled ⏱️"
            binding.tvActiveWorkWindowStatus.setTextColor(Color.parseColor("#007AFF"))
            binding.tvActiveWorkWindowCountdown.text = if (days > 0) {
                String.format(Locale.US, "Opens in: %dd %02dh %02dm %02ds", days, hours, minutes, seconds)
            } else {
                String.format(Locale.US, "Opens in: %02dh %02dm %02ds", hours, minutes, seconds)
            }
            binding.tvActiveWorkWindowCountdown.setTextColor(Color.parseColor("#FF9F0A"))
            val intervalDays = SecureStorage.getInt(this, SecureStorage.KEY_WORK_WINDOW_INTERVAL_DAYS, 1)
            val intervalStr = if (intervalDays == 1) "Daily" else "Every $intervalDays days"
            val durationMins = SecureStorage.getInt(this, SecureStorage.KEY_WORK_WINDOW_DURATION_MINS, 60)
            binding.tvActiveWorkWindowDetails.text = "$intervalStr ($durationMins mins) apps unlock automatically for video uploads."
        }
    }

    private fun updateActiveAlarmLiveCountdown(now: Long) {
        val isAlarmEnabled = com.focuskiosk.alarm.ExtremeAlarmManager.isAlarmEnabled(this)
        if (!isAlarmEnabled) {
            binding.cardActiveAlarmStatus.visibility = View.GONE
            return
        }

        binding.cardActiveAlarmStatus.visibility = View.VISIBLE
        val alarmHour = SecureStorage.getInt(this, SecureStorage.KEY_ALARM_HOUR, 6)
        val alarmMinute = SecureStorage.getInt(this, SecureStorage.KEY_ALARM_MINUTE, 30)
        val amPm = if (alarmHour >= 12) "PM" else "AM"
        val hour12 = if (alarmHour % 12 == 0) 12 else alarmHour % 12
        binding.tvActiveAlarmTitle.text = String.format(Locale.getDefault(), "Extreme Alarm: %d:%02d %s ⏰", hour12, alarmMinute, amPm)

        var nextAlarmMs = SecureStorage.getLong(this, SecureStorage.KEY_NEXT_ALARM_TIMESTAMP_MS, 0L)
        if (nextAlarmMs <= now) {
            nextAlarmMs = com.focuskiosk.alarm.ExtremeAlarmManager.calculateNextAlarmMs(this)
            SecureStorage.putLong(this, SecureStorage.KEY_NEXT_ALARM_TIMESTAMP_MS, nextAlarmMs)
        }

        val rem = (nextAlarmMs - now).coerceAtLeast(0L)
        val hours = rem / 3600_000L
        val minutes = (rem % 3600_000L) / 60_000L
        val seconds = (rem % 60_000L) / 1000L

        binding.tvActiveAlarmCountdown.text = String.format(Locale.US, "Rings in: %02dh %02dm %02ds", hours, minutes, seconds)
    }

    private fun setupAlarmControls() {
        val isEnabled = SecureStorage.getBoolean(this, SecureStorage.KEY_ALARM_ENABLED, true)
        binding.switchExtremeAlarm.isChecked = isEnabled
        binding.layoutAlarmConfig.visibility = if (isEnabled) View.VISIBLE else View.GONE

        updateAlarmSummary()

        binding.switchExtremeAlarm.setOnCheckedChangeListener { _, isChecked ->
            SecureStorage.putBoolean(this, SecureStorage.KEY_ALARM_ENABLED, isChecked)
            binding.layoutAlarmConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                com.focuskiosk.alarm.ExtremeAlarmManager.scheduleAlarm(this)
                Toast.makeText(this, "Extreme Alarm Enabled", Toast.LENGTH_SHORT).show()
            } else {
                com.focuskiosk.alarm.ExtremeAlarmManager.cancelAlarm(this)
                Toast.makeText(this, "Extreme Alarm Disabled", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPickAlarmTime.setOnClickListener {
            val currentHour = SecureStorage.getInt(this, SecureStorage.KEY_ALARM_HOUR, 6)
            val currentMinute = SecureStorage.getInt(this, SecureStorage.KEY_ALARM_MINUTE, 30)
            android.app.TimePickerDialog(
                this,
                { _, h, m ->
                    SecureStorage.putInt(this, SecureStorage.KEY_ALARM_HOUR, h)
                    SecureStorage.putInt(this, SecureStorage.KEY_ALARM_MINUTE, m)
                    updateAlarmSummary()
                    if (binding.switchExtremeAlarm.isChecked) {
                        com.focuskiosk.alarm.ExtremeAlarmManager.scheduleAlarm(this)
                    }
                },
                currentHour,
                currentMinute,
                false
            ).show()
        }

        binding.btnAlarmCredentials.setOnClickListener {
            showAlarmCredentialsDialog()
        }

        binding.btnTestAlarm.setOnClickListener {
            Toast.makeText(this, "⚡ Launching Test Alarm in 2 seconds...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                delay(2000L)
                SecureStorage.putBoolean(this@SetupWizardActivity, SecureStorage.KEY_ALARM_RINGING, true)
                com.focuskiosk.alarm.ExtremeAlarmService.start(this@SetupWizardActivity)
                val intent = Intent(this@SetupWizardActivity, com.focuskiosk.alarm.ExtremeAlarmActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }
        }
    }

    private fun updateAlarmSummary() {
        val h = SecureStorage.getInt(this, SecureStorage.KEY_ALARM_HOUR, 6)
        val m = SecureStorage.getInt(this, SecureStorage.KEY_ALARM_MINUTE, 30)
        val userId = SecureStorage.getString(this, SecureStorage.KEY_ALARM_USER_ID, "jisan")
        val amPm = if (h >= 12) "PM" else "AM"
        val hour12 = if (h % 12 == 0) 12 else h % 12
        val timeStr = String.format(Locale.getDefault(), "%d:%02d %s", hour12, m, amPm)

        binding.btnPickAlarmTime.text = "Time: $timeStr"
        binding.btnAlarmCredentials.text = "PC ID: $userId"
        binding.tvAlarmSummary.text = "Daily at $timeStr (PC Code Verification)"
    }

    private fun showAlarmCredentialsDialog() {
        val context = this
        val userId = SecureStorage.getString(context, SecureStorage.KEY_ALARM_USER_ID, "jisan")
        val password = SecureStorage.getString(context, SecureStorage.KEY_ALARM_PASSWORD, "1234")

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val tvHint = android.widget.TextView(context).apply {
            text = "Set the ID & Password you will enter on your PC Web Portal to get the dismissal code:"
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 13f
            setPadding(0, 0, 0, 24)
        }

        val etUser = android.widget.EditText(context).apply {
            hint = "PC User ID (e.g. jisan)"
            setText(userId)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#636366"))
        }

        val etPass = android.widget.EditText(context).apply {
            hint = "PC Password (e.g. 1234)"
            setText(password)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#636366"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        layout.addView(tvHint)
        layout.addView(etUser)
        layout.addView(etPass)

        androidx.appcompat.app.AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("🖥️ PC Web Portal Credentials")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newId = etUser.text.toString().trim()
                val newPass = etPass.text.toString().trim()
                if (newId.isNotEmpty()) {
                    SecureStorage.putString(context, SecureStorage.KEY_ALARM_USER_ID, newId)
                }
                if (newPass.isNotEmpty()) {
                    SecureStorage.putString(context, SecureStorage.KEY_ALARM_PASSWORD, newPass)
                }
                updateAlarmSummary()
                Toast.makeText(context, "Credentials Saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSetupMode() {
        binding.activeLockPanel.visibility = View.GONE
        binding.searchRowLayout.visibility = View.VISIBLE
        binding.rvSelectApps.visibility = View.VISIBLE
        binding.controlsPanel.visibility = View.VISIBLE
        binding.btnActivateLock.isEnabled = true
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

        // Directly show Apple confirmation dialog without blocking on accessibility service
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

        // Persist work window settings
        val workWindowEnabled = binding.switchWorkWindow.isChecked
        SecureStorage.putBoolean(this, SecureStorage.KEY_WORK_WINDOW_ENABLED, workWindowEnabled)
        SecureStorage.putInt(this, SecureStorage.KEY_WORK_WINDOW_INTERVAL_DAYS, selectedIntervalDays)
        SecureStorage.putInt(this, SecureStorage.KEY_WORK_WINDOW_START_HOUR, selectedWorkWindowStartHour)
        SecureStorage.putInt(this, SecureStorage.KEY_WORK_WINDOW_START_MINUTE, selectedWorkWindowStartMinute)
        SecureStorage.putInt(this, SecureStorage.KEY_WORK_WINDOW_DURATION_MINS, selectedWorkWindowDurationMins)

        // Persist Extreme Wake-up Alarm settings
        val alarmEnabled = binding.switchExtremeAlarm.isChecked
        SecureStorage.putBoolean(this, SecureStorage.KEY_ALARM_ENABLED, alarmEnabled)
        if (alarmEnabled) {
            com.focuskiosk.alarm.ExtremeAlarmManager.scheduleAlarm(this)
        } else {
            com.focuskiosk.alarm.ExtremeAlarmManager.cancelAlarm(this)
        }

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
                // Schedule work / upload window if enabled
                if (workWindowEnabled) {
                    com.focuskiosk.policy.WorkWindowScheduler.scheduleNextWindow(this@SetupWizardActivity)
                }
            } catch (e: Exception) {
                Log.e("SetupWizard", "Error activating focus lock", e)
            }

            withContext(Dispatchers.Main) {
                SecureStorage.setSetupCompleted(this@SetupWizardActivity, true)

                // NOTE: We deliberately DO NOT disable SetupWizardActivity component.
                // Keeping the component enabled ensures the app icon is never removed from the phone!

                // Return user to the native stock home launcher
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
                syncLockState()
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
