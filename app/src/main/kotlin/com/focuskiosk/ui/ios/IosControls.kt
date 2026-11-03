package com.focuskiosk.ui.ios

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.focuskiosk.R

/**
 * UISwitch replica: 51×31 track, 27pt knob, spring-like slide and color cross-fade.
 */
class IosSwitch(context: Context) : View(context) {

    private val trackOn = Ios.color(context, R.color.ios_green)
    private val trackOff = Ios.color(context, R.color.ios_switch_off)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26000000 }
    private val rect = RectF()
    private var progress = 0f
    private var animator: ValueAnimator? = null

    var isChecked: Boolean = false
        private set

    var onCheckedChange: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { setChecked(!isChecked, animate = true, fromUser = true) }
    }

    fun setChecked(checked: Boolean, animate: Boolean = false, fromUser: Boolean = false) {
        if (checked == isChecked && (animator?.isRunning != true)) {
            progress = if (checked) 1f else 0f
            invalidate()
            return
        }
        isChecked = checked
        animator?.cancel()
        val target = if (checked) 1f else 0f
        if (animate) {
            animator = ValueAnimator.ofFloat(progress, target).apply {
                duration = 260
                interpolator = PathInterpolator(0.25f, 1.2f, 0.5f, 1f)
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                start()
            }
            Ios.tap(this)
        } else {
            progress = target
            invalidate()
        }
        if (fromUser) onCheckedChange?.invoke(checked)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Ios.dp(context, 51f), Ios.dp(context, 31f))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val p = progress.coerceIn(0f, 1f)
        trackPaint.color = ColorUtils.blendARGB(trackOff, trackOn, p)
        if (!isEnabled) trackPaint.alpha = 110
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, trackPaint)

        val inset = Ios.dpf(context, 2f)
        val radius = h / 2f - inset
        val travel = w - 2 * inset - 2 * radius
        val cx = inset + radius + travel * progress
        val cy = h / 2f
        canvas.drawCircle(cx, cy + Ios.dpf(context, 0.8f), radius + Ios.dpf(context, 0.4f), shadowPaint)
        canvas.drawCircle(cx, cy, radius, knobPaint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = isChecked
    }
}

/** UIStepper replica: [ − | + ] in a rounded gray capsule. */
@SuppressLint("ViewConstructor")
class IosStepper(
    context: Context,
    private val min: Int,
    private val max: Int,
    initial: Int,
    private val onChange: (Int) -> Unit
) : LinearLayout(context) {

    var value: Int = initial.coerceIn(min, max)
        private set

    private val minus: TextView
    private val plus: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ios.rounded(Ios.color(context, R.color.ios_fill), Ios.dpf(context, 8f))
        clipToOutline = true
        minus = segment("−")
        plus = segment("+")
        addView(minus)
        addView(View(context).apply {
            setBackgroundColor(Ios.color(context, R.color.ios_separator))
            layoutParams = LayoutParams(Ios.hairline(context), Ios.dp(context, 18f))
        })
        addView(plus)
        minus.setOnClickListener { step(-1) }
        plus.setOnClickListener { step(1) }
        refresh()
    }

    private fun segment(symbol: String): TextView = Ios.text(context, 22f, Ios.Weight.REGULAR, R.color.ios_label, symbol).apply {
        gravity = Gravity.CENTER
        layoutParams = LayoutParams(Ios.dp(context, 47f), Ios.dp(context, 32f))
        background = Ios.cellHighlight(context)
        isClickable = true
    }

    private fun step(delta: Int) {
        val next = (value + delta).coerceIn(min, max)
        if (next == value) return
        value = next
        Ios.tick(this)
        refresh()
        onChange(value)
    }

    private fun refresh() {
        minus.alpha = if (value > min) 1f else 0.3f
        plus.alpha = if (value < max) 1f else 0.3f
    }
}

/** UISegmentedControl replica. */
@SuppressLint("ViewConstructor")
class IosSegmented(
    context: Context,
    private val items: List<String>,
    selected: Int,
    private val onSelect: (Int) -> Unit
) : LinearLayout(context) {

    private val labels = mutableListOf<TextView>()
    var selectedIndex: Int = selected
        private set

    init {
        orientation = HORIZONTAL
        val pad = Ios.dp(context, 2f)
        setPadding(pad, pad, pad, pad)
        background = Ios.rounded(Ios.color(context, R.color.ios_fill), Ios.dpf(context, 9f))
        items.forEachIndexed { i, title ->
            val tv = Ios.text(context, 13f, Ios.Weight.MEDIUM, R.color.ios_label, title).apply {
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(0, Ios.dp(context, 28f), 1f)
                setOnClickListener { select(i, fromUser = true) }
            }
            labels.add(tv)
            addView(tv)
        }
        select(selected, fromUser = false)
    }

    fun select(index: Int, fromUser: Boolean) {
        selectedIndex = index
        labels.forEachIndexed { i, tv ->
            val on = i == index
            tv.background = if (on) Ios.rounded(Ios.color(context, R.color.ios_segment_selected), Ios.dpf(context, 7f)) else null
            tv.elevation = if (on) Ios.dpf(context, 1.5f) else 0f
            tv.typeface = Ios.font(context, if (on) Ios.Weight.SEMIBOLD else Ios.Weight.MEDIUM)
        }
        if (fromUser && index >= 0) {
            Ios.tick(this)
            onSelect(index)
        }
    }
}

object IosPill {
    /** Compact UIDatePicker-style value capsule. */
    fun create(context: Context, value: String, onClick: () -> Unit): TextView =
        Ios.text(context, 17f, Ios.Weight.REGULAR, R.color.ios_label, value).apply {
            gravity = Gravity.CENTER
            val h = Ios.dp(context, 11f)
            val v = Ios.dp(context, 7f)
            setPadding(h, v, h, v)
            Ios.tabularDigits(this)
            background = Ios.cellHighlight(context, Ios.rounded(Ios.color(context, R.color.ios_fill), Ios.dpf(context, 7f)))
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            setOnClickListener { onClick() }
        }
}

/** Translucent square HUD ("Copied", "Saved") like the iOS system HUD. */
object IosHud {
    fun show(activity: Activity, message: String, icon: Int = R.drawable.ic_ios_checkmark) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val ctx = activity
        val size = Ios.dp(ctx, 150f)
        val hud = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ios.rounded(Ios.color(ctx, R.color.ios_hud), Ios.dpf(ctx, 16f))
            elevation = Ios.dpf(ctx, 12f)
            addView(ImageView(ctx).apply {
                setImageResource(icon)
                setColorFilter(Ios.color(ctx, R.color.ios_secondary_label))
                layoutParams = LinearLayout.LayoutParams(Ios.dp(ctx, 52f), Ios.dp(ctx, 52f))
            })
            addView(Ios.text(ctx, 15f, Ios.Weight.SEMIBOLD, R.color.ios_secondary_label, message).apply {
                gravity = Gravity.CENTER
                setPadding(Ios.dp(ctx, 12f), Ios.dp(ctx, 10f), Ios.dp(ctx, 12f), 0)
            })
            alpha = 0f
            scaleX = 0.9f
            scaleY = 0.9f
        }
        decor.addView(hud, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        hud.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).withEndAction {
            hud.animate().alpha(0f).setStartDelay(1100).setDuration(260).withEndAction {
                decor.removeView(hud)
            }.start()
        }.start()
    }
}

/** Blocks parent (e.g. bottom sheet) from stealing vertical drags while touching [view]. */
@SuppressLint("ClickableViewAccessibility")
fun View.keepTouchesFromParent() {
    setOnTouchListener { v, event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
            v.parent?.requestDisallowInterceptTouchEvent(true)
        }
        false
    }
}

/** Thin rounded UIProgressView. */
class IosProgressBar(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ios.color(context, R.color.ios_fill_strong) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ios.color(context, R.color.ios_blue) }
    private val rect = RectF()

    var progress: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    fun setFillColor(colorInt: Int) { fill.color = colorInt; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, track)
        if (progress > 0f) {
            rect.set(0f, 0f, (width * progress).coerceAtLeast(height.toFloat()), height.toFloat())
            canvas.drawRoundRect(rect, r, r, fill)
        }
    }
}
