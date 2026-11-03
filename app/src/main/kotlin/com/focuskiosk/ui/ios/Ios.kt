package com.focuskiosk.ui.ios

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.focuskiosk.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Ios
 * ───
 * Small design-system toolkit that recreates the iOS look on Android:
 * Inter (SF-like) type scale, system colors, hairlines, pressed-cell highlights
 * and the colored rounded-square icons used by iOS Settings.
 */
object Ios {

    enum class Weight(val fontRes: Int) {
        LIGHT(R.font.inter_light),
        REGULAR(R.font.inter_regular),
        MEDIUM(R.font.inter_medium),
        SEMIBOLD(R.font.inter_semibold),
        BOLD(R.font.inter_bold)
    }

    private val fontCache = HashMap<Int, Typeface>()

    fun font(context: Context, weight: Weight): Typeface =
        fontCache.getOrPut(weight.fontRes) {
            runCatching { ResourcesCompat.getFont(context, weight.fontRes) }.getOrNull() ?: Typeface.DEFAULT
        }

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    fun dpf(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    /** iOS separators are 0.5pt; never thinner than one physical pixel. */
    fun hairline(context: Context): Int =
        max(1, (0.5f * context.resources.displayMetrics.density).roundToInt())

    fun color(context: Context, @ColorRes res: Int): Int = ContextCompat.getColor(context, res)

    fun text(
        context: Context,
        sizeSp: Float,
        weight: Weight = Weight.REGULAR,
        @ColorRes colorRes: Int = R.color.ios_label,
        value: CharSequence? = null
    ): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = font(context, weight)
        setTextColor(color(context, colorRes))
        includeFontPadding = false
        letterSpacing = when {
            sizeSp >= 28f -> -0.02f
            sizeSp >= 17f -> -0.012f
            else -> -0.004f
        }
        setLineSpacing(dpf(context, 2f), 1f)
        text = value
    }

    fun rounded(colorInt: Int, radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(colorInt)
    }

    fun roundedCorners(colorInt: Int, tl: Float, tr: Float, br: Float, bl: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
            setColor(colorInt)
        }

    fun oval(colorInt: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(colorInt)
    }

    /** Gray flash on touch, exactly like a UITableViewCell highlight. */
    fun cellHighlight(context: Context, normal: Drawable? = null): StateListDrawable = StateListDrawable().apply {
        setExitFadeDuration(220)
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
            setColor(color(context, R.color.ios_cell_pressed))
        })
        addState(intArrayOf(), normal ?: GradientDrawable().apply { setColor(0) })
    }

    /** iOS Settings-style glyph tile: white symbol on a colored rounded square. */
    fun iconTile(context: Context, @DrawableRes icon: Int, @ColorRes tint: Int, sizeDp: Float = 29f): View {
        val size = dp(context, sizeDp)
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = rounded(color(context, tint), dpf(context, sizeDp * 0.24f))
            addView(ImageView(context).apply {
                setImageResource(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = dp(context, sizeDp * 0.17f)
                setPadding(pad, pad, pad, pad)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    fun hairlineView(context: Context, @ColorRes colorRes: Int = R.color.ios_separator): View = View(context).apply {
        setBackgroundColor(color(context, colorRes))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hairline(context))
    }

    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun tap(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun copyToClipboard(context: Context, label: String, value: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    fun tabularDigits(view: TextView) {
        view.fontFeatureSettings = "tnum"
    }

    fun centered(view: TextView): TextView = view.apply { gravity = Gravity.CENTER }
}
