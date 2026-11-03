package com.focuskiosk.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.focuskiosk.R
import com.focuskiosk.alarm.AlarmItem
import com.focuskiosk.alarm.AlarmStore
import com.focuskiosk.alarm.ExtremeAlarmManager
import com.focuskiosk.ui.ios.Ios
import com.focuskiosk.ui.ios.IosAlert
import com.focuskiosk.ui.ios.IosRow
import com.focuskiosk.ui.ios.IosSegmented
import com.focuskiosk.ui.ios.IosSheet
import com.focuskiosk.ui.ios.IosWheelGroup
import com.focuskiosk.ui.ios.IosWheelView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Formatting helpers and the modal sheets used by the main screen. */
object FocusFormat {

    fun time(hour: Int, minute: Int): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return String.format(Locale.US, "%d:%02d %s", h12, minute, if (hour >= 12) "PM" else "AM")
    }

    fun duration(totalMins: Int): String {
        val d = totalMins / 1440
        val h = (totalMins % 1440) / 60
        val m = totalMins % 60
        val parts = mutableListOf<String>()
        if (d > 0) parts.add(if (d == 1) "1 day" else "$d days")
        if (h > 0) parts.add("$h hr")
        if (m > 0) parts.add("$m min")
        return if (parts.isEmpty()) "0 min" else parts.joinToString(" ")
    }

    fun repeat(intervalDays: Int): String = if (intervalDays <= 1) "Every day" else "Every $intervalDays days"

    fun dayAndTime(ms: Long, now: Long = System.currentTimeMillis()): String {
        val target = Calendar.getInstance().apply { timeInMillis = ms }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val tomorrow = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, 1) }
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            same(target, today) -> "Today at $time"
            same(target, tomorrow) -> "Tomorrow at $time"
            else -> SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault()).format(Date(ms))
        }
    }

    fun countdown(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val d = total / 86_400
        val h = (total % 86_400) / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (d > 0) String.format(Locale.US, "%dd %02d:%02d:%02d", d, h, m, s)
        else String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    fun relative(ms: Long): String {
        val mins = ((ms + 59_999L) / 60_000L).coerceAtLeast(0L)
        val d = mins / 1440
        val h = (mins % 1440) / 60
        val m = mins % 60
        return when {
            d > 0 -> "in ${d}d ${h}h"
            h > 0 -> "in ${h}h ${m}m"
            else -> "in ${m}m"
        }
    }
}

object FocusSheets {

    private fun timeWheels(context: Context, hour: Int, minute: Int): Pair<IosWheelGroup, () -> Pair<Int, Int>> {
        val group = IosWheelGroup(context)
        val hours = IosWheelView(context, (1..12).map { it.toString() }, cyclic = true, gravityAlign = Gravity.END)
        val minutes = IosWheelView(context, (0..59).map { String.format(Locale.US, "%02d", it) }, cyclic = true, gravityAlign = Gravity.START)
        val ampm = IosWheelView(context, listOf("AM", "PM"), cyclic = false, gravityAlign = Gravity.START)
        group.addWheel(hours, 78f)
        group.addWheel(minutes, 78f)
        group.addWheel(ampm, 72f)
        hours.select((if (hour % 12 == 0) 12 else hour % 12) - 1)
        minutes.select(minute)
        ampm.select(if (hour >= 12) 1 else 0)
        return group to {
            val h12 = hours.currentIndex() + 1
            val pm = ampm.currentIndex() == 1
            val h24 = (h12 % 12) + if (pm) 12 else 0
            h24 to minutes.currentIndex()
        }
    }

    private fun wheelHolder(context: Context, group: IosWheelGroup): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ios.dp(context, 16f), Ios.dp(context, 8f), Ios.dp(context, 16f), 0)
        addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun time(context: Context, title: String, hour: Int, minute: Int, onDone: (Int, Int) -> Unit) {
        val sheet = IosSheet(context, title)
        val (group, read) = timeWheels(context, hour, minute)
        sheet.content.addView(wheelHolder(context, group))
        sheet.onConfirm = {
            val (h, m) = read()
            onDone(h, m)
            true
        }
        sheet.show()
    }

    /** Focus-lock length: presets plus days / hours / minutes wheels. */
    fun lockDuration(context: Context, initialMins: Int, onDone: (Int) -> Unit) {
        val sheet = IosSheet(context, "Lock Duration")
        val presets = listOf(15, 60, 240, 1440, 10080, 43200)
        val labels = listOf("15m", "1h", "4h", "1d", "7d", "30d")

        val days = IosWheelView(context, (0..365).map { it.toString() }, cyclic = false, gravityAlign = Gravity.END)
        val hours = IosWheelView(context, (0..23).map { it.toString() }, cyclic = true, gravityAlign = Gravity.END)
        val mins = IosWheelView(context, (0..59).map { it.toString() }, cyclic = true, gravityAlign = Gravity.END)
        val group = IosWheelGroup(context).apply {
            addWheel(days, 60f, "days")
            addWheel(hours, 52f, "hours")
            addWheel(mins, 52f, "min")
        }
        val preview = Ios.centered(Ios.text(context, 15f, Ios.Weight.REGULAR, R.color.ios_secondary_label)).apply {
            setPadding(0, Ios.dp(context, 14f), 0, 0)
        }

        fun total() = days.currentIndex() * 1440 + hours.currentIndex() * 60 + mins.currentIndex()
        fun refresh() {
            val t = total()
            preview.text = if (t <= 0) "Choose how long the lock lasts" else
                "Unlocks ${FocusFormat.dayAndTime(System.currentTimeMillis() + t * 60_000L)}"
            sheet.setConfirmEnabled(t > 0)
        }
        fun apply(totalMins: Int) {
            days.select(totalMins / 1440)
            hours.select((totalMins % 1440) / 60)
            mins.select(totalMins % 60)
            group.post { refresh() }
        }

        val segmented = IosSegmented(context, labels, presets.indexOf(initialMins)) { i -> apply(presets[i]) }
        listOf(days, hours, mins).forEach { w -> w.onSelected = { refresh(); segmented.select(presets.indexOf(total()), fromUser = false) } }

        sheet.content.addView(segmented, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ios.dp(context, 16f), Ios.dp(context, 4f), Ios.dp(context, 16f), Ios.dp(context, 8f))
        })
        sheet.content.addView(wheelHolder(context, group))
        sheet.content.addView(preview)
        sheet.onConfirm = {
            val t = total()
            if (t > 0) onDone(t)
            t > 0
        }
        sheet.show()
        apply(initialMins)
    }

    /** Upload window length: hours (0–12) and minutes (5-minute steps). */
    fun windowLength(context: Context, initialMins: Int, onDone: (Int) -> Unit) {
        val sheet = IosSheet(context, "Unlock For")
        val minuteSteps = (0..55 step 5).toList()
        val hours = IosWheelView(context, (0..12).map { it.toString() }, cyclic = false, gravityAlign = Gravity.END)
        val mins = IosWheelView(context, minuteSteps.map { it.toString() }, cyclic = true, gravityAlign = Gravity.END)
        val group = IosWheelGroup(context).apply {
            addWheel(hours, 56f, "hours")
            addWheel(mins, 56f, "min")
        }
        fun total() = hours.currentIndex() * 60 + minuteSteps[mins.currentIndex()]
        val note = Ios.centered(Ios.text(context, 15f, Ios.Weight.REGULAR, R.color.ios_secondary_label)).apply {
            setPadding(Ios.dp(context, 24f), Ios.dp(context, 14f), Ios.dp(context, 24f), 0)
        }
        fun refresh() {
            val t = total()
            note.text = if (t < 5) "Pick at least 5 minutes" else "Everything unlocks for ${FocusFormat.duration(t)}, then locks again."
            sheet.setConfirmEnabled(t >= 5)
        }
        hours.onSelected = { refresh() }
        mins.onSelected = { refresh() }
        sheet.content.addView(wheelHolder(context, group))
        sheet.content.addView(note)
        sheet.onConfirm = {
            val t = total()
            if (t >= 5) onDone(t)
            t >= 5
        }
        sheet.show()
        hours.select((initialMins / 60).coerceIn(0, 12))
        mins.select(((initialMins % 60) / 5).coerceIn(0, minuteSteps.size - 1))
        group.post { refresh() }
    }

    /** iOS Clock-style Add / Edit Alarm sheet. */
    fun alarmEditor(context: Context, existing: AlarmItem?, onChanged: () -> Unit) {
        val sheet = IosSheet(context, if (existing == null) "Add Alarm" else "Edit Alarm", "Save")
        val (group, read) = timeWheels(context, existing?.hour ?: 6, existing?.minute ?: 0)
        sheet.content.addView(wheelHolder(context, group))

        val days = (existing?.days ?: emptySet()).toMutableSet()
        val section = sheet.form.section()

        val repeatRow = IosRow(context, "Repeat", null, null, R.color.ios_blue, R.color.ios_label)
        val circles = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(Ios.dp(context, 12f), 0, Ios.dp(context, 12f), Ios.dp(context, 14f))
        }
        val order = listOf(Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY)
        val letters = listOf("S", "M", "T", "W", "T", "F", "S")
        val dayViews = mutableListOf<TextView>()
        fun paint() {
            order.forEachIndexed { i, day ->
                val on = day in days
                dayViews[i].background = Ios.oval(Ios.color(context, if (on) R.color.ios_orange else R.color.ios_fill))
                dayViews[i].setTextColor(Ios.color(context, if (on) R.color.ios_white else R.color.ios_label))
            }
            repeatRow.value = AlarmItem.summarize(days)
        }
        order.forEachIndexed { i, day ->
            val tv = Ios.centered(Ios.text(context, 15f, Ios.Weight.SEMIBOLD, R.color.ios_label, letters[i])).apply {
                val size = Ios.dp(context, 38f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    val m = Ios.dp(context, 3f)
                    setMargins(m, 0, m, 0)
                }
                setOnClickListener {
                    if (day in days) days.remove(day) else days.add(day)
                    Ios.tick(this)
                    paint()
                }
            }
            dayViews.add(tv)
            circles.addView(tv)
        }
        val repeatBlock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        repeatBlock.addView(repeatRow.view)
        repeatBlock.addView(circles)
        section.addCustom(repeatBlock)
        paint()

        val labelRow = section.row("Label")
        val labelInput = EditText(context).apply {
            setText(existing?.label?.takeIf { it != AlarmItem.DEFAULT_LABEL } ?: "")
            hint = AlarmItem.DEFAULT_LABEL
            textSize = 17f
            typeface = Ios.font(context, Ios.Weight.REGULAR)
            setTextColor(Ios.color(context, R.color.ios_secondary_label))
            setHintTextColor(Ios.color(context, R.color.ios_tertiary_label))
            background = null
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(Ios.dp(context, 180f), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        labelRow.setAccessory(labelInput)

        sheet.form.section(footer = "Stops only with the 12-digit code from your PC portal. Volume, power menu and restart can't silence it.")

        if (existing != null) {
            sheet.form.section().buttonRow("Delete Alarm", R.color.ios_red, center = true) {
                ExtremeAlarmManager.delete(context, existing.id)
                sheet.dismiss()
                onChanged()
            }
        }

        sheet.onConfirm = {
            val (h, m) = read()
            val item = (existing ?: AlarmItem(hour = h, minute = m)).copy(
                hour = h,
                minute = m,
                days = days.toSet(),
                label = labelInput.text.toString().trim().ifEmpty { AlarmItem.DEFAULT_LABEL },
                isEnabled = true
            )
            ExtremeAlarmManager.upsert(context, item)
            onChanged()
            true
        }
        sheet.show()
    }

    fun portalId(context: Context, onSaved: () -> Unit) {
        val alert = IosAlert(context)
            .title("Portal ID")
            .message("Type this ID on ${AlarmStore.PORTAL_URL_DISPLAY}. The password is new for every alarm and appears on the alarm screen.")
        val idField = alert.field("Portal ID", AlarmStore.portalUser(context))
        alert.action("Cancel", IosAlert.Style.CANCEL)
            .action("Save", IosAlert.Style.PREFERRED) {
                if (idField.value.isNotEmpty()) {
                    AlarmStore.setPortalUser(context, idField.value)
                    onSaved()
                }
            }
            .show()
    }
}
