package com.focuskiosk.ui.ios

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.focuskiosk.R
import kotlin.math.abs

/**
 * UIPickerView replica: a snapping, optionally looping wheel with the 3D cylinder fade.
 * Several wheels are placed inside an [IosWheelGroup], which draws the shared
 * rounded selection band behind the middle row.
 */
@SuppressLint("ViewConstructor")
class IosWheelView(
    context: Context,
    private val items: List<String>,
    private val cyclic: Boolean,
    private val gravityAlign: Int = Gravity.CENTER,
    private val textSizeSp: Float = 22f
) : RecyclerView(context) {

    companion object {
        const val VISIBLE_ROWS = 5
        private const val LOOPS = 2000
    }

    private val rowHeight = Ios.dp(context, 36f)
    private val lm = LinearLayoutManager(context)
    private val snap = LinearSnapHelper()
    private var lastCenter = -1

    var selectedIndex: Int = 0
        private set

    var onSelected: ((Int) -> Unit)? = null

    init {
        layoutManager = lm
        adapter = WheelAdapter()
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        val pad = rowHeight * (VISIBLE_ROWS / 2)
        setPadding(0, pad, 0, pad)
        snap.attachToRecyclerView(this)
        // The cylinder fade is done per row in applyCylinder(); a View fading edge would
        // start at the padding line and dim the selected row.
        isVerticalFadingEdgeEnabled = false
        keepTouchesFromParent()
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                applyCylinder()
                val center = centerPosition()
                if (center != RecyclerView.NO_POSITION && center != lastCenter) {
                    if (lastCenter != -1) Ios.tick(this@IosWheelView)
                    lastCenter = center
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == SCROLL_STATE_IDLE) {
                    val center = centerPosition()
                    if (center != RecyclerView.NO_POSITION && items.isNotEmpty()) {
                        selectedIndex = center % items.size
                        onSelected?.invoke(selectedIndex)
                    }
                }
            }
        })
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(rowHeight * VISIBLE_ROWS, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        applyCylinder()
    }

    fun select(index: Int) {
        if (items.isEmpty()) return
        selectedIndex = index.coerceIn(0, items.size - 1)
        val position = if (cyclic) (LOOPS / 2) * items.size + selectedIndex else selectedIndex
        lastCenter = position
        lm.scrollToPositionWithOffset(position, 0)
        post { applyCylinder() }
    }

    /** Index under the selection band right now, even mid-fling. */
    fun currentIndex(): Int {
        val c = centerPosition()
        return if (c == RecyclerView.NO_POSITION || items.isEmpty()) selectedIndex else c % items.size
    }

    private fun centerPosition(): Int {
        val v = snap.findSnapView(lm) ?: return RecyclerView.NO_POSITION
        return lm.getPosition(v)
    }

    private fun applyCylinder() {
        val mid = height / 2f
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val childMid = (child.top + child.bottom) / 2f
            val d = (childMid - mid) / rowHeight
            val ad = abs(d).coerceAtMost(3f)
            child.alpha = (1f - ad * 0.3f).coerceAtLeast(0.12f)
            child.rotationX = -d * 17f
            val s = 1f - ad * 0.05f
            child.scaleX = s
            child.scaleY = s
        }
    }

    private inner class WheelAdapter : Adapter<ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val tv = Ios.text(parent.context, textSizeSp, Ios.Weight.REGULAR, R.color.ios_label).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeight)
                gravity = gravityAlign or Gravity.CENTER_VERTICAL
                maxLines = 1
                val side = Ios.dp(parent.context, 6f)
                setPadding(side, 0, side, 0)
                Ios.tabularDigits(this)
            }
            return object : ViewHolder(tv) {}
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val tv = holder.itemView as TextView
            tv.text = items[position % items.size]
            tv.setOnClickListener { smoothScrollBy(0, tv.top + tv.height / 2 - height / 2) }
        }

        override fun getItemCount(): Int = if (cyclic) items.size * LOOPS else items.size
    }
}

/** Hosts wheels side by side with the iOS selection band and optional fixed unit labels. */
class IosWheelGroup(context: Context) : FrameLayout(context) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    init {
        val band = View(context).apply {
            background = Ios.rounded(Ios.color(context, R.color.ios_fill), Ios.dpf(context, 8f))
        }
        addView(band, LayoutParams(LayoutParams.MATCH_PARENT, Ios.dp(context, 36f), Gravity.CENTER_VERTICAL).apply {
            val side = Ios.dp(context, 10f)
            setMargins(side, 0, side, 0)
        })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun addWheel(wheel: IosWheelView, widthDp: Float, unit: String? = null) {
        row.addView(wheel, LinearLayout.LayoutParams(Ios.dp(context, widthDp), LinearLayout.LayoutParams.WRAP_CONTENT))
        if (unit != null) {
            row.addView(Ios.text(context, 17f, Ios.Weight.SEMIBOLD, R.color.ios_label, unit).apply {
                layoutParams = LinearLayout.LayoutParams(Ios.dp(context, 52f), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
    }
}
