package com.focuskiosk.alarm

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.focuskiosk.R
import com.focuskiosk.storage.SecureStorage
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ExtremeAlarmActivity
 * ────────────────────
 * Unstoppable, fullscreen alarm screen that turns on the display and overrides the lock screen.
 * Volume keys, back navigation, and home interruptions are blocked or neutralized.
 * Only entering the dynamic 6-digit code obtained from the PC Web Portal dismisses the alarm.
 */
class ExtremeAlarmActivity : AppCompatActivity() {

    private lateinit var tvAlarmClockTime: TextView
    private lateinit var tvUserIdHint: TextView
    private lateinit var etAlarmCode: EditText
    private lateinit var tvCodeError: TextView
    private lateinit var btnDismissAlarm: MaterialButton

    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeUpdater = object : Runnable {
        override fun run() {
            updateClockDisplay()
            timeHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindowForLockscreen()

        setContentView(R.layout.activity_extreme_alarm)

        tvAlarmClockTime = findViewById(R.id.tvAlarmClockTime)
        tvUserIdHint = findViewById(R.id.tvUserIdHint)
        etAlarmCode = findViewById(R.id.etAlarmCode)
        tvCodeError = findViewById(R.id.tvCodeError)
        btnDismissAlarm = findViewById(R.id.btnDismissAlarm)

        val userId = SecureStorage.getString(this, SecureStorage.KEY_ALARM_USER_ID, "jisan")
        tvUserIdHint.text = "PC Login ID: $userId"

        updateClockDisplay()

        // Block Back button navigation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Intentionally blocked - user must enter PC code
            }
        })

        // Auto-verify when 6 digits are typed
        etAlarmCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                tvCodeError.visibility = View.INVISIBLE
                if (s?.length == 6) {
                    verifyAndDismiss(s.toString())
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnDismissAlarm.setOnClickListener {
            verifyAndDismiss(etAlarmCode.text.toString())
        }

        // Keep keyboard accessible
        etAlarmCode.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(etAlarmCode, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onResume() {
        super.onResume()
        timeHandler.post(timeUpdater)
        maxAlarmVolume()
    }

    override fun onPause() {
        super.onPause()
        timeHandler.removeCallbacks(timeUpdater)
    }

    private fun updateClockDisplay() {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        tvAlarmClockTime.text = sdf.format(Date())
    }

    private fun configureWindowForLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun maxAlarmVolume() {
        runCatching {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                // Prevent muting or lowering alarm volume
                maxAlarmVolume()
                true
            }
            KeyEvent.KEYCODE_BACK -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun verifyAndDismiss(code: String) {
        val cleanCode = code.trim()
        if (cleanCode.length != 6) {
            tvCodeError.text = "Please enter full 6-digit code from PC."
            tvCodeError.visibility = View.VISIBLE
            return
        }

        val isValid = AlarmCodeGenerator.validateEnteredCode(this, cleanCode)
        if (isValid) {
            Toast.makeText(this, "✅ Alarm Dismissed! Good morning!", Toast.LENGTH_LONG).show()

            // Stop audio and vibration
            ExtremeAlarmService.stop(this)

            // Reschedule next cycle
            ExtremeAlarmManager.onAlarmDismissed(this)

            finish()
        } else {
            tvCodeError.text = "Invalid code. Check your PC Alarm Portal."
            tvCodeError.visibility = View.VISIBLE
            etAlarmCode.setText("")
        }
    }
}
