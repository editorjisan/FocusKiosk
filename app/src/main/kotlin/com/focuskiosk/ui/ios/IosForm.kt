package com.focuskiosk.ui.ios

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.focuskiosk.R

/**
 * IosForm
 * ───────
 * Builds an iOS "inset grouped" table (the look of the iOS Settings app) inside a
 * vertical LinearLayout: uppercase section headers, rounded cards, inset hairline
 * separators, footers, and the standard row types (value, chevron, switch, button).
 *
 * @param elevated true inside sheets, where iOS uses the lighter elevated cell color.
 */
class IosForm(private val context: Context, val container: LinearLayout, private val elevated: Boolean = false) {

    fun clear() = container.removeAllViews()

    fun section(
        header: String? = null,
        footer: String? = null,
        action: String? = null,
        onAction: (() -> Unit)? = null
    ): IosSection {
        val section = IosSection(context, header, footer, action, onAction, elevated)
        container.addView(section.view)
        return section
    }

    fun addView(view: View) = container.addView(view)
}

class IosSection internal constructor(
    private val context: Context,
    header: String?,
    footer: String?,
    action: String?,
    onAction: (() -> Unit)?,
    private val elevated: Boolean
) {
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val side = Ios.dp(context, 16f)
            setMargins(side, Ios.dp(context, if (header != null) 22f else 30f), side, 0)
        }
    }

    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Ios.rounded(
            Ios.color(context, if (elevated) R.color.ios_cell_elevated else R.color.ios_cell),
            Ios.dpf(context, 10f)
        )
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private val footerView: TextView = Ios.text(context, 13f, Ios.Weight.REGULAR, R.color.ios_secondary_label).apply {
        setPadding(Ios.dp(context, 16f), Ios.dp(context, 7f), Ios.dp(context, 16f), 0)
        setLineSpacing(Ios.dpf(context, 3f), 1f)
    }

    private val units = mutableListOf<Pair<View, View>>() // separator, row

    var headerActionView: TextView? = null
        private set

    init {
        if (header != null) {
            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(Ios.dp(context, 16f), 0, Ios.dp(context, 4f), Ios.dp(context, 7f))
            }
            headerRow.addView(Ios.text(context, 13f, Ios.Weight.REGULAR, R.color.ios_secondary_label, header.uppercase()).apply {
                letterSpacing = 0.02f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (action != null) {
                headerActionView = Ios.text(context, 16f, Ios.Weight.MEDIUM, R.color.ios_blue, action).apply {
                    setPadding(Ios.dp(context, 12f), Ios.dp(context, 4f), Ios.dp(context, 12f), Ios.dp(context, 2f))
                    setOnClickListener { onAction?.invoke() }
                }
                headerRow.addView(headerActionView)
            }
            view.addView(headerRow)
        }
        view.addView(card)
        view.addView(footerView)
        setFooter(footer)
    }

    fun setFooter(text: CharSequence?) {
        footerView.text = text
        footerView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun setVisible(visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Adds an arbitrary row; [insetDp] is where the separator above it starts. */
    fun addCustom(row: View, insetDp: Float = 16f): View {
        val separator = Ios.hairlineView(context, if (elevated) R.color.ios_separator_elevated else R.color.ios_separator).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = Ios.dp(context, insetDp)
        }
        card.addView(separator)
        card.addView(row)
        units.add(separator to row)
        refresh()
        return row
    }

    fun setRowVisible(row: View, visible: Boolean) {
        row.visibility = if (visible) View.VISIBLE else View.GONE
        refresh()
    }

    fun refresh() {
        var first = true
        for ((sep, row) in units) {
            if (row.visibility != View.VISIBLE) {
                sep.visibility = View.GONE
                continue
            }
            sep.visibility = if (first) View.GONE else View.VISIBLE
            first = false
        }
        card.visibility = if (first) View.GONE else View.VISIBLE
    }

    fun row(
        title: CharSequence,
        subtitle: CharSequence? = null,
        @DrawableRes icon: Int? = null,
        @ColorRes iconColor: Int = R.color.ios_blue,
        value: CharSequence? = null,
        chevron: Boolean = false,
        @ColorRes titleColor: Int = R.color.ios_label,
        onClick: (() -> Unit)? = null
    ): IosRow {
        val row = IosRow(context, title, subtitle, icon, iconColor, titleColor)
        row.value = value
        if (chevron) row.setAccessory(ImageView(context).apply {
            setImageResource(R.drawable.ic_ios_chevron)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ios.dp(context, 10f)
            }
        })
        if (onClick != null) {
            row.view.background = Ios.cellHighlight(context)
            row.view.setOnClickListener { onClick() }
        }
        row.section = this
        addCustom(row.view, if (icon != null) 60f else 16f)
        return row
    }

    fun switchRow(
        title: CharSequence,
        subtitle: CharSequence? = null,
        @DrawableRes icon: Int? = null,
        @ColorRes iconColor: Int = R.color.ios_green,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): IosRow {
        val row = row(title, subtitle, icon, iconColor)
        val sw = IosSwitch(context).apply {
            setChecked(checked)
            onCheckedChange = onChange
        }
        row.switch = sw
        row.setAccessory(sw)
        row.view.setOnClickListener { sw.setChecked(!sw.isChecked, animate = true, fromUser = true) }
        return row
    }

    fun buttonRow(
        title: CharSequence,
        @ColorRes colorRes: Int = R.color.ios_blue,
        center: Boolean = false,
        @DrawableRes icon: Int? = null,
        @ColorRes iconColor: Int = R.color.ios_blue,
        onClick: () -> Unit
    ): IosRow {
        val row = row(title, icon = icon, iconColor = iconColor, titleColor = colorRes, onClick = onClick)
        if (center) {
            row.titleView.gravity = Gravity.CENTER
            row.titleView.layoutParams = (row.titleView.layoutParams as LinearLayout.LayoutParams).apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
        }
        return row
    }
}

class IosRow internal constructor(
    context: Context,
    title: CharSequence,
    subtitle: CharSequence?,
    @DrawableRes icon: Int?,
    @ColorRes iconColor: Int,
    @ColorRes titleColor: Int
) {
    internal var section: IosSection? = null
    var switch: IosSwitch? = null
        internal set

    val view = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Ios.dp(context, 46f)
        setPadding(Ios.dp(context, 16f), Ios.dp(context, 10f), Ios.dp(context, 16f), Ios.dp(context, 10f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private val textColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    val titleView: TextView = Ios.text(context, 17f, Ios.Weight.REGULAR, titleColor, title).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    val subtitleView: TextView = Ios.text(context, 13f, Ios.Weight.REGULAR, R.color.ios_secondary_label).apply {
        setPadding(0, Ios.dp(context, 3f), 0, 0)
    }

    val valueView: TextView = Ios.text(context, 17f, Ios.Weight.REGULAR, R.color.ios_secondary_label).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.END
        setPadding(Ios.dp(context, 10f), 0, 0, 0)
        Ios.tabularDigits(this)
    }

    private val accessoryHolder = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        if (icon != null) {
            view.addView(Ios.iconTile(context, icon, iconColor).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = Ios.dp(context, 15f)
            })
        }
        textColumn.addView(titleView)
        textColumn.addView(subtitleView)
        view.addView(textColumn)
        view.addView(valueView)
        view.addView(accessoryHolder)
        subtitleView.text = subtitle
        subtitleView.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        valueView.visibility = View.GONE
    }

    var title: CharSequence?
        get() = titleView.text
        set(v) { titleView.text = v }

    var subtitle: CharSequence?
        get() = subtitleView.text
        set(v) {
            subtitleView.text = v
            subtitleView.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

    var value: CharSequence?
        get() = valueView.text
        set(v) {
            valueView.text = v
            valueView.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

    fun setAccessory(accessory: View) {
        accessoryHolder.removeAllViews()
        accessoryHolder.addView(accessory)
        if (accessory !is ImageView) {
            val lp = accessory.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginStart = Ios.dp(view.context, 10f)
            accessory.layoutParams = lp
        }
    }

    fun setVisible(visible: Boolean) {
        section?.setRowVisible(view, visible) ?: run { view.visibility = if (visible) View.VISIBLE else View.GONE }
    }
}
