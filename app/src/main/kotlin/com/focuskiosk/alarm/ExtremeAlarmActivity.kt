package com.focuskiosk.alarm

import android.animation.ObjectAnimator
import android.app.KeyguardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.focuskiosk.R
import com.focuskiosk.databinding.ActivityExtremeAlarmBinding
import com.focuskiosk.ui.ios.Ios
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * ExtremeAlarmActivity
 * ────────────────────
 * Full-screen ringing screen styled after the iOS lock screen. The phone is pinned to this
 * screen (lock task, see [AlarmGuard]) until the rotating 12-digit code from the PC portal
 * is typed on the passcode-style keypad. Volume, Back, Home and Recents do nothing.
 */
class ExtremeAlarmActivity : AppCompatActivity() {

    private lateinit var b: ActivityExtremeAlarmBinding
    private val handler = Handler(Looper.getMainLooper())
    private val entered = StringBuilder()
    private val cells = mutableListOf<TextView>()
    private var verifying = false
    private var dismissed = false

    private val clockTick = object : Runnable {
        override fun run() {
            // Stopped elsewhere (safety cap, another screen): leave quietly.
            if (!dismissed && !AlarmStore.isRinging(this@ExtremeAlarmActivity)) {
                AlarmGuard.release(this@ExtremeAlarmActivity, this@ExtremeAlarmActivity)
                finishAndRemoveTask()
                return
            }
            val now = Date()
            b.tvAlarmClock.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
            b.tvAlarmDate.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AlarmStore.isRinging(this)) {
            finish()
            return
        }
        showOverLockScreen()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityExtremeAlarmBinding.inflate(layoutInflater)
        setContentView(b.root)
        hideSystemBars()

        ViewCompat.setOnApplyWindowInsetsListener(b.alarmColumn) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        val alarm = AlarmStore.getAlarms(this).firstOrNull { it.id == AlarmStore.ringingAlarmId(this) }
        b.tvAlarmLabel.text = when {
            AlarmStore.ringingAlarmId(this) == TEST_ALARM_ID -> "Test Alarm"
            alarm != null -> alarm.label
            else -> "Alarm"
        }
        // A new password for every alarm; it is thrown away once the alarm is stopped.
        val otp = AlarmStore.ensureOneTimePassword(this)
        b.tvAlarmPortal.text = "Open ${AlarmStore.PORTAL_URL_DISPLAY} and sign in with"
        b.tvCredId.text = AlarmStore.portalUser(this)
        b.tvCredPassword.text = AlarmStore.formatPassword(otp)
        b.credCard.background = GradientDrawable().apply {
            cornerRadius = Ios.dpf(this@ExtremeAlarmActivity, 14f)
            setColor(0x1FFFFFFF)
        }
        b.tvAlarmKeyCheck.text = "Key check ${AlarmCodeGenerator.keyCheck(this)}"

        buildCodeCells()
        buildKeypad()
        renderCode()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
    }

    override fun onResume() {
        super.onResume()
        if (!AlarmStore.isRinging(this)) {
            finish()
            return
        }
        isForeground = true
        AlarmGuard.engage(this)
        AlarmGuard.enterLockTask(this)
        if (!ExtremeAlarmService.isRunning) ExtremeAlarmService.start(this)
        hideSystemBars()
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        handler.removeCallbacks(clockTick)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Without device owner lock task, bounce straight back to the alarm.
        if (!dismissed && AlarmStore.isRinging(this)) {
            handler.postDelayed({ ExtremeAlarmService.launchAlarmScreen(this) }, 300L)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val digit = event.keyCode - KeyEvent.KEYCODE_0
            if (digit in 0..9) {
                pressDigit(digit.toString())
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                pressDelete()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showOverLockScreen() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.requestDismissKeyguard(this, null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ── Code cells ────────────────────────────────────────────────────────────

    private fun buildCodeCells() {
        val screenW = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val cellW = min(25f, (screenW - 32f - 2 * 12f - 9 * 4f) / 12f)
        for (group in 0 until 3) {
            val g = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 4) {
                val tv = TextView(this).apply {
                    gravity = Gravity.CENTER
                    textSize = 21f
                    typeface = Ios.font(this@ExtremeAlarmActivity, Ios.Weight.SEMIBOLD)
                    setTextColor(0xFFFFFFFF.toInt())
                    includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(Ios.dp(context, cellW), Ios.dp(context, 36f)).apply {
                        if (i > 0) marginStart = Ios.dp(context, 4f)
                    }
                }
                cells.add(tv)
                g.addView(tv)
            }
            b.codeRow.addView(g, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (group > 0) marginStart = Ios.dp(this@ExtremeAlarmActivity, 12f)
            })
        }
    }

    private fun renderCode() {
        cells.forEachIndexed { i, tv ->
            val filled = i < entered.length
            val active = i == entered.length
            tv.text = if (filled) entered[i].toString() else ""
            tv.background = GradientDrawable().apply {
                cornerRadius = Ios.dpf(this@ExtremeAlarmActivity, 7f)
                setColor(if (filled) 0x33FFFFFF else 0x1AFFFFFF)
                if (active) setStroke(Ios.dp(this@ExtremeAlarmActivity, 1.5f), 0xFFFF9F0A.toInt())
            }
        }
    }

    // ── Keypad ────────────────────────────────────────────────────────────────

    private fun buildKeypad() {
        val metrics = resources.displayMetrics
        val screenH = metrics.heightPixels / metrics.density
        val keyDp = ((screenH - 500f) / 4.7f).coerceIn(56f, 78f)
        val gapDp = (keyDp * 0.28f).coerceIn(12f, 24f)
        val letters = mapOf(
            "2" to "A B C", "3" to "D E F", "4" to "G H I", "5" to "J K L",
            "6" to "M N O", "7" to "P Q R S", "8" to "T U V", "9" to "W X Y Z"
        )
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "del")
        keys.forEachIndexed { index, key ->
            val size = Ios.dp(this, keyDp)
            val params = GridLayout.LayoutParams(GridLayout.spec(index / 3), GridLayout.spec(index % 3)).apply {
                width = size
                height = size
                val half = Ios.dp(this@ExtremeAlarmActivity, gapDp / 2f)
                setMargins(half, Ios.dp(this@ExtremeAlarmActivity, 6f), half, Ios.dp(this@ExtremeAlarmActivity, 6f))
            }
            val view: View = when (key) {
                "" -> View(this)
                "del" -> FrameLayout(this).apply {
                    addView(ImageView(context).apply {
                        setImageResource(R.drawable.ic_sym_backspace)
                        setColorFilter(0xCCFFFFFF.toInt())
                    }, FrameLayout.LayoutParams(Ios.dp(context, 30f), Ios.dp(context, 30f), Gravity.CENTER))
                    contentDescription = "Delete"
                    background = keyBackground(transparent = true)
                    setOnClickListener { pressDelete() }
                    setOnLongClickListener { entered.clear(); renderCode(); true }
                }
                else -> LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = keyBackground(transparent = false)
                    addView(TextView(context).apply {
                        text = key
                        textSize = 32f
                        typeface = Ios.font(context, Ios.Weight.LIGHT)
                        setTextColor(0xFFFFFFFF.toInt())
                        includeFontPadding = false
                        gravity = Gravity.CENTER
                    })
                    letters[key]?.let { l ->
                        addView(TextView(context).apply {
                            text = l
                            textSize = 9f
                            letterSpacing = 0.12f
                            typeface = Ios.font(context, Ios.Weight.SEMIBOLD)
                            setTextColor(0xB3FFFFFF.toInt())
                            includeFontPadding = false
                            gravity = Gravity.CENTER
                            setPadding(0, Ios.dp(context, 2f), 0, 0)
                        })
                    }
                    setOnClickListener { pressDigit(key) }
                }
            }
            view.defaultFocusHighlightEnabled = false
            view.isFocusable = false
            b.keypad.addView(view, params)
        }
    }

    private fun keyBackground(transparent: Boolean) = StateListDrawable().apply {
        setExitFadeDuration(260)
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Ios.color(this@ExtremeAlarmActivity, R.color.alarm_key_pressed))
        })
        addState(intArrayOf(), GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (transparent) 0 else Ios.color(this@ExtremeAlarmActivity, R.color.alarm_key))
        })
    }

    private fun pressDigit(d: String) {
        if (verifying || entered.length >= AlarmCodeGenerator.CODE_LENGTH) return
        b.keypad.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        entered.append(d)
        b.tvAlarmError.text = " "
        renderCode()
        if (entered.length == AlarmCodeGenerator.CODE_LENGTH) {
            verifying = true
            handler.postDelayed({ verify() }, 180L)
        }
    }

    private fun pressDelete() {
        if (verifying || entered.isEmpty()) return
        b.keypad.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        entered.deleteCharAt(entered.length - 1)
        renderCode()
    }

    private fun verify() {
        if (AlarmCodeGenerator.validateEnteredCode(this, entered.toString())) {
            dismissed = true
            AlarmGuard.release(this, this)
            ExtremeAlarmManager.onDismissed(this)
            showSuccess()
        } else {
            b.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            b.tvAlarmError.text = "Wrong code. Check the ID and password on your PC."
            ObjectAnimator.ofFloat(b.codeRow, View.TRANSLATION_X, 0f, 22f, -20f, 16f, -12f, 7f, -3f, 0f).apply {
                duration = 460
                start()
            }
            handler.postDelayed({
                entered.clear()
                verifying = false
                renderCode()
            }, 520L)
        }
    }

    private fun showSuccess() {
        b.successOverlay.alpha = 0f
        b.successOverlay.visibility = View.VISIBLE
        b.ivSuccess.scaleX = 0.6f
        b.ivSuccess.scaleY = 0.6f
        b.successOverlay.animate().alpha(1f).setDuration(200).start()
        b.ivSuccess.animate().scaleX(1f).scaleY(1f).setDuration(320)
            .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        handler.postDelayed({ finishAndRemoveTask() }, 1300L)
    }

    companion object {
        const val TEST_ALARM_ID = "test"

        @Volatile
        var isForeground = false
            private set
    }
}
