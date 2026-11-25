package com.focuskiosk.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.focuskiosk.R
import com.focuskiosk.policy.PolicyEnforcer
import com.focuskiosk.sms.SmsFilter
import com.focuskiosk.ui.ios.Ios
import com.focuskiosk.ui.ios.IosForm

/** Messages filter: master switch, promo auto-hide, and a per-sender list (like the Allowed Apps picker). */
class SmsFilterActivity : AppCompatActivity() {
    private lateinit var form: IosForm

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.ios_bg))
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, i ->
            val b = i.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(Ios.dp(this, 0f), b.top, 0, b.bottom); i
        }
        title = "Messages Filter"
        form = IosForm(this, list)
        PolicyEnforcer.grantSmsPermissionSilently(this)
        build()
    }

    private fun build() {
        form.clear()
        val master = form.section("Messages Filter")
        master.switchRow("Filter Promotional SMS", icon = R.drawable.ic_sym_lock, iconColor = R.color.ios_orange,
            checked = SmsFilter.isEnabled(this)) { on -> SmsFilter.setEnabled(this, on); build() }
        if (SmsFilter.isEnabled(this)) {
            master.switchRow("Auto-detect promos (GP, Robi…)", icon = R.drawable.ic_sym_apps, iconColor = R.color.ios_blue,
                checked = SmsFilter.isAutoPromo(this)) { on -> SmsFilter.setAutoPromo(this, on); build() }
            master.buttonRow("Filter Off — show everything", colorRes = R.color.ios_red, center = true) {
                SmsFilter.setEnabled(this, false); build()
            }
            master.setFooter("OTP, bKash, bank and verification messages are never hidden. Hidden senders only lose their notification; the SMS stays in Messages.")
        } else {
            master.setFooter("Off — every SMS notification shows normally.")
            return
        }
        val senders = SmsFilter.senders(this)
        val sec = form.section("Senders (${senders.size})")
        if (senders.isEmpty()) {
            sec.setFooter("No SMS senders found. Allow SMS access, then reopen this screen.")
            return
        }
        senders.forEach { s ->
            sec.switchRow(s.name, subtitle = "${s.count} msgs · ${s.sample}", icon = R.drawable.ic_sym_apps,
                iconColor = R.color.ios_green, checked = !SmsFilter.isHidden(this, s.name, s.sample)
            ) { keep -> SmsFilter.setSender(this, s.name, hide = !keep) }
        }
        sec.setFooter("ON = keep notifications. OFF = hide them.")
    }
}
