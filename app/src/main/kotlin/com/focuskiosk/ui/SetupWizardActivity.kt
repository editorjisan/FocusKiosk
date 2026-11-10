package com.focuskiosk.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.focuskiosk.BuildConfig
import com.focuskiosk.R
import com.focuskiosk.alarm.AlarmItem
import com.focuskiosk.alarm.AlarmStore
import com.focuskiosk.alarm.ExtremeAlarmActivity
import com.focuskiosk.alarm.ExtremeAlarmManager
import com.focuskiosk.databinding.ActivitySetupWizardBinding
import com.focuskiosk.policy.KioskRestoreManager
import com.focuskiosk.policy.PolicyEnforcer
import com.focuskiosk.policy.WorkWindowScheduler
import com.focuskiosk.storage.SecureStorage
import com.focuskiosk.ui.ios.Ios
import com.focuskiosk.ui.ios.IosAlert
import com.focuskiosk.ui.ios.IosForm
import com.focuskiosk.ui.ios.IosHud
import com.focuskiosk.ui.ios.IosPill
import com.focuskiosk.ui.ios.IosProgressBar
import com.focuskiosk.ui.ios.IosRow
import com.focuskiosk.ui.ios.IosSection
import com.focuskiosk.ui.ios.IosStepper
import com.focuskiosk.ui.ios.IosSwitch
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
 * The whole app on one iOS Settings-style screen.
 *
 * Setup mode: lock duration, allowed apps, upload window schedule, protection switches,
 * alarms and PC portal login, with a pinned "Start Focus Lock" button.
 * Locked mode: live countdown, upload window status (with "I'm Done — Lock Now"),
 * alarms and portal settings.
 */
open class SetupWizardActivity : AppCompatActivity() {

    private lateinit var b: ActivitySetupWizardBinding
    private lateinit var form: IosForm
    private val handler = Handler(Looper.getMainLooper())
    private var bottomInset = 0

    private var lockedUntil = 0L
    private var durationMins = 60

    // Live-updating pieces of the current render
    private var updateRow: IosRow? = null
    private var updateBusy = false
    private var updateValue: String? = null
    private var updateSubtitle: String? = null
    private var focusSection: IosSection? = null
    private var heroCountdown: TextView? = null
    private var heroProgress: IosProgressBar? = null
    private var windowStatusRow: IosRow? = null
    private var windowDoneRow: IosRow? = null
    private var lastWindowSync = 0L

    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 1000L)
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted -> Log.i("SetupWizard", "POST_NOTIFICATIONS granted=$isGranted") }

    private fun ensureNotificationPermission() {
        PolicyEnforcer.grantNotificationPermissionSilently(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force-enable all launcher components to recover from any previous disabled states
        runCatching {
            listOf("com.focuskiosk.MainActivity", "com.focuskiosk.ui.SetupWizardActivity").forEach {
                packageManager.setComponentEnabledSetting(
                    android.content.ComponentName(this, it),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }

        if (!PolicyEnforcer.isDeviceOwner(this)) {
            showOwnerError()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        b = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(b.root)
        form = IosForm(this, b.form)
        durationMins = SecureStorage.getInt(this, SecureStorage.KEY_LAST_DURATION_MINS, 60).coerceAtLeast(1)

        setupChrome()
        ensureNotificationPermission()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { InstalledApps.load(this@SetupWizardActivity) } }
            delay(1200L)
            triggerUpdateFlow()
        }

        // Ensure sideloading and APK installs are never restricted
        PolicyEnforcer.unblockAppInstalls(this)
        PolicyEnforcer.unrestrictSettingsSilently(this)
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return
        PolicyEnforcer.unblockAppInstalls(this)
        PolicyEnforcer.unrestrictSettingsSilently(this)
        render()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ── Chrome: insets, collapsing large title, primary button ────────────────

    private fun setupChrome() {
        b.tvVersionFooter.text = "Focus Kiosk ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val blue = Ios.color(this, R.color.ios_blue)
        b.btnPrimary.background = StateListDrawable().apply {
            setExitFadeDuration(150)
            addState(intArrayOf(android.R.attr.state_pressed),
                Ios.rounded(ColorUtils.blendARGB(blue, Color.BLACK, 0.18f), Ios.dpf(this@SetupWizardActivity, 14f)))
            addState(intArrayOf(), Ios.rounded(blue, Ios.dpf(this@SetupWizardActivity, 14f)))
        }
        b.btnPrimary.typeface = Ios.font(this, Ios.Weight.SEMIBOLD)
        b.btnPrimary.setOnClickListener { confirmActivation() }

        val navBg = ColorDrawable(Ios.color(this, R.color.ios_nav_bar)).apply { alpha = 0 }
        b.navBar.background = navBg

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.navBar.setPadding(0, bars.top, 0, 0)
            b.bottomBar.setPadding(0, 0, 0, bars.bottom)
            bottomInset = bars.bottom
            b.content.setPadding(0, bars.top + Ios.dp(this, 44f), 0, b.content.paddingBottom)
            updateBottomPadding()
            insets
        }

        b.scroll.setOnScrollChangeListener { _: View, _: Int, scrollY: Int, _: Int, _: Int ->
            val title = b.tvLargeTitle
            val start = title.top - b.content.paddingTop
            val p = ((scrollY - start).toFloat() / title.height.coerceAtLeast(1)).coerceIn(0f, 1f)
            b.tvNavTitle.alpha = p
            b.navHairline.alpha = p
            navBg.alpha = (p * 255).toInt()
            title.alpha = 1f - p * 0.9f
        }
    }

    private fun updateBottomPadding() {
        val extra = if (b.bottomBar.visibility == View.VISIBLE) Ios.dp(this, 77f) else Ios.dp(this, 12f)
        b.content.setPadding(0, b.content.paddingTop, 0, bottomInset + extra)
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private fun readLockState(): Pair<Boolean, Long> {
        val sp = createDeviceProtectedStorageContext().getSharedPreferences("focus_kiosk_prefs", Context.MODE_PRIVATE)
        val epochTime = sp.getLong("unlock_epoch_time", 0L)
        val secureTs = runCatching { SecureStorage.getUnlockTimestampMs(this) }.getOrDefault(0L)
        val unlockEpoch = if (epochTime > 0L) epochTime else secureTs
        val isLocked = sp.getBoolean("lock_active", false) || runCatching { SecureStorage.isLockActive(this) }.getOrDefault(false)
        return isLocked to unlockEpoch
    }

    private fun render() {
        // One-time clean-slate reset for v1.5.2 to ensure user can set up fresh
        if (!SecureStorage.getBoolean(this, "reset_v152_clean_slate", false)) {
            SecureStorage.putBoolean(this, "reset_v152_clean_slate", true)
            KioskRestoreManager.restoreAllApps(this)
            WorkWindowScheduler.resetAllWindows(this)
            renderSetup()
            return
        }

        val (isLocked, unlockAt) = readLockState()
        val now = System.currentTimeMillis()
        val isWindowActive = WorkWindowScheduler.isWindowActive(this)

        if (isLocked && unlockAt in 1..now) {
            Log.i("SetupWizard", "Unlock time reached — restoring apps.")
            KioskRestoreManager.restoreAllApps(this)
            renderSetup()
        } else if (isWindowActive) {
            renderUploadWindowActive(unlockAt)
        } else if (isLocked && unlockAt > now) {
            renderLocked(unlockAt)
        } else {
            renderSetup()
        }
    }

    private fun resetLiveViews() {
        form.clear()
        updateRow = null
        focusSection = null
        heroCountdown = null
        heroProgress = null
        windowStatusRow = null
        windowDoneRow = null
    }

    private fun renderSetup() {
        lockedUntil = 0L
        resetLiveViews()
        b.tvHeaderSubtitle.text = "Choose the apps you need. Everything else stays locked until the timer ends."
        b.bottomBar.visibility = View.VISIBLE
        updateBottomPadding()

        addUpdateSection()
        addFocusSetupSection()
        addUploadWindowSetupSection()
        addProtectionSection()
        addAlarmSection()
        addPortalSection()
        addResetSection()
    }

    private fun renderUploadWindowActive(unlockAt: Long) {
        lockedUntil = unlockAt
        resetLiveViews()
        b.tvHeaderSubtitle.text = "Upload Window Open. Your phone is fully unlocked for uploading."
        b.bottomBar.visibility = View.GONE
        updateBottomPadding()

        addUploadWindowStatusSection()
        addUploadWindowSetupSection()
        addFocusSetupSection()
        addProtectionSection()
        addAlarmSection()
        addPortalSection()
        addResetSection()
        addUpdateSection()
        onTick()
    }

    private fun renderLocked(unlockAt: Long) {
        lockedUntil = unlockAt
        resetLiveViews()
        b.tvHeaderSubtitle.text = "Focus Lock is on. Only your allowed apps open until it ends."
        b.bottomBar.visibility = View.GONE
        updateBottomPadding()

        addLockHero(unlockAt)
        addUploadWindowStatusSection()
        addAlarmSection()
        addPortalSection()
        addResetSection()
        addUpdateSection()
        onTick()
    }

    private fun onTick() {
        val now = System.currentTimeMillis()
        if (lockedUntil > 0L) {
            val remaining = lockedUntil - now
            if (remaining <= 0L) {
                KioskRestoreManager.restoreAllApps(this)
                render()
                return
            }
            heroCountdown?.text = FocusFormat.countdown(remaining)
            val startedAt = SecureStorage.getLong(this, SecureStorage.KEY_LOCK_START_MS, 0L)
            if (startedAt in 1 until lockedUntil) {
                heroProgress?.progress = (now - startedAt).toFloat() / (lockedUntil - startedAt)
            }
            updateWindowStatus(now)
        } else {
            refreshFocusFooter()
        }
    }

    // ── Sections: setup mode ──────────────────────────────────────────────────

    private fun addUpdateSection() {
        val section = form.section()
        updateRow = section.row(
            "Software Update",
            subtitle = updateSubtitle,
            icon = R.drawable.ic_sym_gear,
            iconColor = R.color.ios_gray,
            value = updateValue ?: "v${BuildConfig.VERSION_NAME}",
            chevron = true
        ) { triggerUpdateFlow() }
    }

    private fun addFocusSetupSection() {
        val section = form.section("Focus Lock")
        focusSection = section
        lateinit var pill: TextView
        val durationRow = section.row("Duration", icon = R.drawable.ic_sym_hourglass, iconColor = R.color.ios_indigo) {
            pill.performClick()
        }
        pill = IosPill.create(this, FocusFormat.duration(durationMins)) {
            FocusSheets.lockDuration(this, durationMins) { mins ->
                durationMins = mins
                SecureStorage.putInt(this, SecureStorage.KEY_LAST_DURATION_MINS, mins)
                pill.text = FocusFormat.duration(mins)
                refreshFocusFooter()
            }
        }
        durationRow.setAccessory(pill)

        val allowed = (SecureStorage.getPackageSet(this, SecureStorage.KEY_WHITELIST_PACKAGES) - packageName).size
        section.row(
            "Allowed Apps",
            icon = R.drawable.ic_sym_apps,
            iconColor = R.color.ios_blue,
            value = if (allowed == 0) "None" else allowed.toString(),
            chevron = true
        ) { openAppPicker() }
        refreshFocusFooter()
    }

    private fun refreshFocusFooter() {
        val section = focusSection ?: return
        val unlock = System.currentTimeMillis() + durationMins * 60_000L
        section.setFooter("If you start now, everything except your allowed apps stays locked until ${FocusFormat.dayAndTime(unlock)}.")
    }

    private fun addUploadWindowSetupSection() {
        val section = form.section("Upload Window")
        val configRows = mutableListOf<IosRow>()

        fun footer() {
            val cfg = WorkWindowScheduler.config(this)
            section.setFooter(
                if (WorkWindowScheduler.isEnabled(this)) {
                    "${FocusFormat.repeat(cfg.intervalDays)} at ${FocusFormat.time(cfg.hour, cfg.minute)}, your phone fully unlocks " +
                        "for ${FocusFormat.duration(cfg.durationMins)} — every app, notification and website — so you can post to " +
                        "Instagram, YouTube and more. Then it locks again by itself."
                } else {
                    "Turn on to unlock your phone on a schedule for uploading, then lock it again automatically."
                }
            )
        }

        section.switchRow(
            "Upload Window",
            icon = R.drawable.ic_sym_upload,
            iconColor = R.color.ios_green,
            checked = WorkWindowScheduler.isEnabled(this)
        ) { on ->
            WorkWindowScheduler.setEnabled(this, on)
            configRows.forEach { it.setVisible(on) }
            footer()
        }

        val cfg = WorkWindowScheduler.config(this)
        val repeatRow = section.row("Repeat", subtitle = FocusFormat.repeat(cfg.intervalDays), icon = R.drawable.ic_sym_repeat, iconColor = R.color.ios_orange)
        repeatRow.setAccessory(IosStepper(this, 1, WorkWindowScheduler.MAX_INTERVAL_DAYS, cfg.intervalDays) { days ->
            WorkWindowScheduler.saveConfig(this, WorkWindowScheduler.config(this).copy(intervalDays = days))
            repeatRow.subtitle = FocusFormat.repeat(days)
            footer()
        })

        lateinit var startPill: TextView
        val startRow = section.row("Starts At", icon = R.drawable.ic_sym_clock, iconColor = R.color.ios_blue) {
            startPill.performClick()
        }
        startPill = IosPill.create(this, FocusFormat.time(cfg.hour, cfg.minute)) {
            val c = WorkWindowScheduler.config(this)
            FocusSheets.time(this, "Starts At", c.hour, c.minute) { h, m ->
                WorkWindowScheduler.saveConfig(this, WorkWindowScheduler.config(this).copy(hour = h, minute = m))
                startPill.text = FocusFormat.time(h, m)
                footer()
            }
        }
        startRow.setAccessory(startPill)

        lateinit var lengthPill: TextView
        val lengthRow = section.row("Unlock For", icon = R.drawable.ic_sym_lock, iconColor = R.color.ios_teal) {
            lengthPill.performClick()
        }
        lengthPill = IosPill.create(this, FocusFormat.duration(cfg.durationMins)) {
            FocusSheets.windowLength(this, WorkWindowScheduler.config(this).durationMins) { mins ->
                WorkWindowScheduler.saveConfig(this, WorkWindowScheduler.config(this).copy(durationMins = mins))
                lengthPill.text = FocusFormat.duration(mins)
                footer()
            }
        }
        lengthRow.setAccessory(lengthPill)

        configRows.addAll(listOf(repeatRow, startRow, lengthRow))
        configRows.forEach { it.setVisible(WorkWindowScheduler.isEnabled(this)) }
        footer()
    }

    private fun addProtectionSection() {
        val section = form.section("Protection", footer = "Leave USB Debugging unblocked if you use Iriun Webcam or connect the phone to your PC.")
        section.switchRow(
            "Block Factory Reset",
            icon = R.drawable.ic_sym_reset,
            iconColor = R.color.ios_red,
            checked = SecureStorage.getBoolean(this, SecureStorage.KEY_BLOCK_FACTORY_RESET, true)
        ) { SecureStorage.putBoolean(this, SecureStorage.KEY_BLOCK_FACTORY_RESET, it) }
        section.switchRow(
            "Block USB Debugging",
            icon = R.drawable.ic_sym_usb,
            iconColor = R.color.ios_gray,
            checked = SecureStorage.getBoolean(this, SecureStorage.KEY_BLOCK_USB_DEBUGGING, false)
        ) { SecureStorage.putBoolean(this, SecureStorage.KEY_BLOCK_USB_DEBUGGING, it) }
    }

    // ── Sections: locked mode ─────────────────────────────────────────────────

    private fun addLockHero(unlockAt: Long) {
        val section = form.section()
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ios.dp(context, 20f), Ios.dp(context, 24f), Ios.dp(context, 20f), Ios.dp(context, 22f))
        }
        hero.addView(Ios.iconTile(this, R.drawable.ic_sym_lock, R.color.ios_blue, 54f))
        hero.addView(Ios.text(this, 17f, Ios.Weight.SEMIBOLD, R.color.ios_label, "Focus Lock Active").apply {
            gravity = Gravity.CENTER
            setPadding(0, Ios.dp(context, 14f), 0, 0)
        })
        heroCountdown = Ios.text(this, 46f, Ios.Weight.SEMIBOLD, R.color.ios_label).apply {
            gravity = Gravity.CENTER
            Ios.tabularDigits(this)
            setPadding(0, Ios.dp(context, 6f), 0, 0)
        }
        hero.addView(heroCountdown)
        hero.addView(Ios.text(this, 15f, Ios.Weight.REGULAR, R.color.ios_secondary_label,
            "Unlocks " + SimpleDateFormat("EEEE, MMM d 'at' h:mm a", Locale.getDefault()).format(Date(unlockAt))).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ios.dp(context, 4f), 0, 0)
        })
        heroProgress = IosProgressBar(this)
        hero.addView(heroProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ios.dp(this, 6f)).apply {
            topMargin = Ios.dp(this@SetupWizardActivity, 18f)
        })
        section.addCustom(hero)
    }

    private fun addUploadWindowStatusSection() {
        if (!WorkWindowScheduler.isEnabled(this)) {
            form.section("Upload Window").row("Upload Window", icon = R.drawable.ic_sym_upload, iconColor = R.color.ios_green, value = "Off")
            return
        }
        val cfg = WorkWindowScheduler.config(this)
        val section = form.section(
            "Upload Window",
            footer = "${FocusFormat.repeat(cfg.intervalDays)} at ${FocusFormat.time(cfg.hour, cfg.minute)} for ${FocusFormat.duration(cfg.durationMins)}. " +
                "During the window every app and website works; afterwards the lock comes back on its own."
        )
        windowStatusRow = section.row("Next Window", icon = R.drawable.ic_sym_upload, iconColor = R.color.ios_green)
        windowDoneRow = section.buttonRow("I'm Done — Lock Now", R.color.ios_red, center = true) { confirmEarlyLock() }
        windowDoneRow?.setVisible(false)
        updateWindowStatus(System.currentTimeMillis())
    }

    private fun updateWindowStatus(now: Long) {
        val row = windowStatusRow ?: return
        val open = WorkWindowScheduler.isWindowActive(this)
        val schedule = WorkWindowScheduler.calculateWindowSchedule(this, now)
        if (open) {
            val end = SecureStorage.getLong(this, SecureStorage.KEY_NEXT_WINDOW_END_MS, 0L)
            row.title = "Open Now"
            row.subtitle = "Locks again at " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(end))
            row.value = FocusFormat.countdown(end - now)
            row.valueView.setTextColor(Ios.color(this, R.color.ios_green))
            windowDoneRow?.setVisible(true)
        } else {
            row.title = "Next Window"
            row.subtitle = FocusFormat.dayAndTime(schedule.nextWindowStartMs, now)
            row.value = FocusFormat.relative(schedule.nextWindowStartMs - now)
            row.valueView.setTextColor(Ios.color(this, R.color.ios_secondary_label))
            windowDoneRow?.setVisible(false)
        }
        // Receivers drive the window; this only repairs a missed alarm while the screen is open.
        if (schedule.isCurrentlyActive != open && now - lastWindowSync > 10_000L) {
            lastWindowSync = now
            lifecycleScope.launch(Dispatchers.IO) {
                WorkWindowScheduler.checkAndSyncWindow(this@SetupWizardActivity)
            }
        }
    }

    private fun confirmEarlyLock() {
        val next = WorkWindowScheduler.calculateWindowSchedule(this, System.currentTimeMillis() + 60_000L).nextWindowStartMs
        IosAlert(this)
            .title("Lock Now?")
            .message("Everything locks again right away. The next upload window opens ${FocusFormat.dayAndTime(next)}.")
            .action("Cancel", IosAlert.Style.CANCEL)
            .action("Lock Now", IosAlert.Style.DESTRUCTIVE) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { WorkWindowScheduler.onWindowEnd(this@SetupWizardActivity, isManualEarlyDone = true) }
                    IosHud.show(this@SetupWizardActivity, "Locked", R.drawable.ic_sym_lock)
                    render()
                }
            }
            .show()
    }

    // ── Shared sections: alarms & PC portal ───────────────────────────────────

    private fun addAlarmSection() {
        val alarms = ExtremeAlarmManager.getAlarms(this)
        val next = ExtremeAlarmManager.nextAlarm(this)
        val footer = buildString {
            append(if (next != null) "Next alarm ${FocusFormat.dayAndTime(next.second)}. " else "No alarms are on. ")
            append("Alarms ring at full volume and stop only with the 12-digit code from your PC.")
        }
        val section = form.section("Alarms", footer = footer, action = "Add") {
            FocusSheets.alarmEditor(this, null) { render() }
        }
        if (alarms.isEmpty()) {
            section.addCustom(Ios.centered(Ios.text(this, 17f, Ios.Weight.REGULAR, R.color.ios_secondary_label, "No Alarms")).apply {
                setPadding(0, Ios.dp(context, 22f), 0, Ios.dp(context, 22f))
            })
        }
        alarms.forEach { section.addCustom(alarmRow(it)) }
        section.buttonRow("Test Alarm", icon = R.drawable.ic_sym_bolt, iconColor = R.color.ios_orange) { confirmTestAlarm() }
    }

    private fun alarmRow(alarm: AlarmItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ios.dp(context, 16f), Ios.dp(context, 8f), Ios.dp(context, 16f), Ios.dp(context, 10f))
            background = Ios.cellHighlight(context)
            setOnClickListener { FocusSheets.alarmEditor(this@SetupWizardActivity, alarm) { render() } }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val timeLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
        }
        val h12 = if (alarm.hour % 12 == 0) 12 else alarm.hour % 12
        val time = Ios.text(this, 46f, Ios.Weight.LIGHT, R.color.ios_label, String.format(Locale.US, "%d:%02d", h12, alarm.minute)).apply {
            Ios.tabularDigits(this)
            letterSpacing = -0.03f
        }
        val ampm = Ios.text(this, 26f, Ios.Weight.LIGHT, R.color.ios_label, if (alarm.hour >= 12) "PM" else "AM").apply {
            setPadding(Ios.dp(context, 3f), 0, 0, 0)
        }
        timeLine.addView(time)
        timeLine.addView(ampm)
        val detail = Ios.text(this, 15f, Ios.Weight.REGULAR, R.color.ios_label,
            if (alarm.isRepeating) {
                val repeat = alarm.repeatSummary()
                "${alarm.label}, ${if (repeat.startsWith("Every") || repeat.startsWith("Week")) repeat.lowercase() else repeat}"
            } else alarm.label
        ).apply { setPadding(0, Ios.dp(context, 2f), 0, 0) }
        texts.addView(timeLine)
        texts.addView(detail)

        fun tint(on: Boolean) {
            val c = Ios.color(this, if (on) R.color.ios_label else R.color.ios_secondary_label)
            time.setTextColor(c)
            ampm.setTextColor(c)
            detail.setTextColor(c)
        }
        tint(alarm.isEnabled)

        val sw = IosSwitch(this).apply {
            setChecked(alarm.isEnabled)
            onCheckedChange = { on ->
                ExtremeAlarmManager.setEnabled(this@SetupWizardActivity, alarm.id, on)
                tint(on)
                if (on) {
                    ExtremeAlarmManager.nextAlarm(this@SetupWizardActivity)?.let { (item, at) ->
                        if (item.id == alarm.id) IosHud.show(this@SetupWizardActivity, FocusFormat.relative(at - System.currentTimeMillis()).replaceFirstChar { it.uppercase() }, R.drawable.ic_sym_alarm)
                    }
                }
                handler.postDelayed({ render() }, 350L)
            }
        }
        row.addView(texts)
        row.addView(sw)
        return row
    }

    private fun confirmTestAlarm() {
        IosAlert(this)
            .title("Test Alarm")
            .message("The alarm rings now at full volume. Its screen shows a one-time password: sign in with it on ${AlarmStore.PORTAL_URL_DISPLAY} from your PC, then type the code shown there.")
            .action("Cancel", IosAlert.Style.CANCEL)
            .action("Ring Now", IosAlert.Style.PREFERRED) {
                ExtremeAlarmManager.startRinging(this, ExtremeAlarmActivity.TEST_ALARM_ID)
            }
            .show()
    }

    private fun addPortalSection() {
        val user = AlarmStore.portalUser(this)
        val section = form.section(
            "PC Portal",
            footer = "When an alarm rings, its screen shows your ID and a new one-time password. Sign in with them on your PC " +
                "and type the 12-digit code it shows. Each password works for one alarm only, so a password someone sees is useless " +
                "and there is no saved password anyone could change to lock you out."
        )
        section.row("Portal Address", subtitle = AlarmStore.PORTAL_URL_DISPLAY, icon = R.drawable.ic_sym_desktop, iconColor = R.color.ios_indigo) {
            Ios.copyToClipboard(this, "Portal", AlarmStore.PORTAL_URL)
            IosHud.show(this, "Link Copied")
        }
        section.row("Portal ID", icon = R.drawable.ic_sym_person, iconColor = R.color.ios_blue, value = user, chevron = true) {
            FocusSheets.portalId(this) { render() }
        }
        section.row("Password", icon = R.drawable.ic_sym_key, iconColor = R.color.ios_gray, value = "New every alarm")
    }

    private fun addResetSection() {
        val section = form.section(
            "Reset & Restore",
            footer = "Reset active focus locks, upload schedules and restore all applications so you can configure setup fresh."
        )
        section.buttonRow("Reset All Windows & Locks", R.color.ios_red, center = true) {
            IosAlert(this)
                .title("Reset All Windows & Locks?")
                .message("This will unlock all apps immediately and reset the current focus lock and upload schedules.")
                .action("Cancel", IosAlert.Style.CANCEL)
                .action("Reset", IosAlert.Style.DESTRUCTIVE) {
                    KioskRestoreManager.restoreAllApps(this@SetupWizardActivity)
                    WorkWindowScheduler.resetAllWindows(this@SetupWizardActivity)
                    IosHud.show(this@SetupWizardActivity, "Reset", R.drawable.ic_sym_reset)
                    render()
                }
                .show()
        }
    }

    // ── Software update ───────────────────────────────────────────────────────

    private fun triggerUpdateFlow() {
        if (updateBusy || !::b.isInitialized) return
        updateBusy = true
        setUpdateState("Checking…", null)
        lifecycleScope.launch {
            com.focuskiosk.updater.SilentUpdateManager.checkAndInstallUpdateWithProgress(this@SetupWizardActivity) { percent, status ->
                val failed = status.startsWith("Failed") || status.contains("failed", ignoreCase = true) || status.startsWith("Update error")
                when {
                    status.startsWith("Already") -> setUpdateState("Up to Date", null)
                    failed -> setUpdateState("Try Again", "Couldn't reach GitHub")
                    status.startsWith("New version") -> setUpdateState("Downloading…", status.substringBefore(" found").removePrefix("New version ") + " available")
                    status.startsWith("Downloading") -> setUpdateState("$percent%", "Downloading update")
                    status.startsWith("Installing") -> setUpdateState("Installing…", "The app restarts by itself")
                    status.startsWith("Installation") -> setUpdateState("Restarting…", "The app restarts by itself")
                }
                if (percent >= 100 || failed) updateBusy = false
            }
        }
    }

    private fun setUpdateState(value: String, subtitle: String?) {
        updateValue = value
        updateSubtitle = subtitle
        updateRow?.value = value
        updateRow?.subtitle = subtitle
    }

    // ── Starting the lock ─────────────────────────────────────────────────────

    private fun openAppPicker() {
        startActivity(Intent(this, AppPickerActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.ios_push_in, R.anim.ios_push_out)
    }

    private fun confirmActivation() {
        ensureNotificationPermission()
        val whitelist = SecureStorage.getPackageSet(this, SecureStorage.KEY_WHITELIST_PACKAGES) - packageName
        if (whitelist.isEmpty()) {
            IosAlert(this)
                .title("Choose Allowed Apps")
                .message("Pick at least one app that should stay available during the lock.")
                .action("Cancel", IosAlert.Style.CANCEL)
                .action("Choose Apps", IosAlert.Style.PREFERRED) { openAppPicker() }
                .show()
            return
        }
        val unlockTs = System.currentTimeMillis() + durationMins * 60_000L
        val windowNote = if (WorkWindowScheduler.isEnabled(this)) {
            val c = WorkWindowScheduler.config(this)
            "\n\nUpload window: ${FocusFormat.repeat(c.intervalDays).lowercase()} at ${FocusFormat.time(c.hour, c.minute)} for ${FocusFormat.duration(c.durationMins)}."
        } else ""
        IosAlert(this)
            .title("Start Focus Lock?")
            .message("${whitelist.size} apps stay available. Everything else locks until ${FocusFormat.dayAndTime(unlockTs)}.$windowNote\n\nThe lock can't be turned off early.")
            .action("Cancel", IosAlert.Style.CANCEL)
            .action("Lock", IosAlert.Style.DESTRUCTIVE) { applyLock(whitelist, System.currentTimeMillis() + durationMins * 60_000L) }
            .show()
    }

    private fun applyLock(whitelist: Set<String>, unlockTs: Long) {
        val finalWhitelist = whitelist + packageName
        b.btnPrimary.isEnabled = false
        b.btnPrimary.text = "Locking…"

        SecureStorage.putPackageSet(this, SecureStorage.KEY_WHITELIST_PACKAGES, finalWhitelist)
        SecureStorage.putLong(this, SecureStorage.KEY_UNLOCK_TIMESTAMP_MS, unlockTs)
        SecureStorage.putLong(this, SecureStorage.KEY_LOCK_START_MS, System.currentTimeMillis())
        // Persist the switches exactly as shown (PolicyEnforcer's own defaults differ for unset keys).
        SecureStorage.putBoolean(this, SecureStorage.KEY_BLOCK_FACTORY_RESET,
            SecureStorage.getBoolean(this, SecureStorage.KEY_BLOCK_FACTORY_RESET, true))
        SecureStorage.putBoolean(this, SecureStorage.KEY_BLOCK_USB_DEBUGGING,
            SecureStorage.getBoolean(this, SecureStorage.KEY_BLOCK_USB_DEBUGGING, false))

        // Upload window cadence starts today; a window already in progress is skipped.
        val windowEnabled = WorkWindowScheduler.isEnabled(this)
        SecureStorage.putBoolean(this, SecureStorage.KEY_WORK_WINDOW_ACTIVE, false)
        WorkWindowScheduler.resetAnchor(this)
        WorkWindowScheduler.skipCurrentWindow(this)

        // Plain + Direct Boot prefs let the watchdog and boot receiver read the lock instantly
        val prefs = listOf(
            getSharedPreferences("focus_kiosk_prefs", Context.MODE_PRIVATE),
            createDeviceProtectedStorageContext().getSharedPreferences("focus_kiosk_prefs", Context.MODE_PRIVATE)
        )
        prefs.forEach { it.edit().putLong("unlock_epoch_time", unlockTs).putBoolean("lock_active", true).apply() }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                PolicyEnforcer.activateFocusLock(this@SetupWizardActivity)
                com.focuskiosk.service.FocusCountdownService.start(this@SetupWizardActivity, unlockTs)
                KioskRestoreManager.scheduleFailSafe(this@SetupWizardActivity, unlockTs)
                if (windowEnabled) WorkWindowScheduler.scheduleNextWindow(this@SetupWizardActivity)
            } catch (e: Exception) {
                Log.e("SetupWizard", "Error activating focus lock", e)
            }
            withContext(Dispatchers.Main) {
                SecureStorage.setSetupCompleted(this@SetupWizardActivity, true)
                b.btnPrimary.isEnabled = true
                b.btnPrimary.text = "Start Focus Lock"
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                render()
            }
        }
    }

    private fun showOwnerError() {
        IosAlert(this)
            .title("Device Owner Required")
            .message("Run this ADB command after a factory reset:\n\nadb shell dpm set-device-owner $packageName/com.focuskiosk.admin.FocusDeviceAdminReceiver")
            .action("OK", IosAlert.Style.PREFERRED) { finish() }
            .show()
    }
}
