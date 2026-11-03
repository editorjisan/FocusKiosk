package com.focuskiosk.ui.ios

import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.focuskiosk.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** UIAlertController (.alert) replica. */
class IosAlert(private val context: Context) {

    enum class Style { DEFAULT, PREFERRED, CANCEL, DESTRUCTIVE }

    private class Action(val title: String, val style: Style, val handler: (() -> Unit)?)
    class Field(val hint: String, val initial: String, val password: Boolean) {
        internal var editText: EditText? = null
        val value: String get() = editText?.text?.toString()?.trim().orEmpty()
    }

    private var title: String? = null
    private var message: CharSequence? = null
    private val actions = mutableListOf<Action>()
    private val fields = mutableListOf<Field>()

    fun title(value: String) = apply { title = value }
    fun message(value: CharSequence) = apply { message = value }
    fun action(title: String, style: Style = Style.DEFAULT, handler: (() -> Unit)? = null) =
        apply { actions.add(Action(title, style, handler)) }

    fun field(hint: String, initial: String = "", password: Boolean = false): Field =
        Field(hint, initial, password).also { fields.add(it) }

    fun show(): Dialog {
        val dialog = Dialog(context, R.style.IosDialog)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ios.rounded(Ios.color(context, R.color.ios_alert), Ios.dpf(context, 14f))
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ios.dp(context, 16f), Ios.dp(context, 19f), Ios.dp(context, 16f), Ios.dp(context, 19f))
        }
        title?.let {
            body.addView(Ios.centered(Ios.text(context, 17f, Ios.Weight.SEMIBOLD, R.color.ios_label, it)))
        }
        message?.let {
            body.addView(Ios.centered(Ios.text(context, 13f, Ios.Weight.REGULAR, R.color.ios_label, it)).apply {
                setPadding(0, Ios.dp(context, 4f), 0, 0)
                setLineSpacing(Ios.dpf(context, 3f), 1f)
            })
        }
        if (fields.isNotEmpty()) {
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = Ios.rounded(Ios.color(context, R.color.ios_cell_elevated), Ios.dpf(context, 8f))
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Ios.dp(context, 16f)
                }
            }
            fields.forEachIndexed { i, f ->
                if (i > 0) box.addView(Ios.hairlineView(context, R.color.ios_separator_elevated))
                val et = EditText(context).apply {
                    hint = f.hint
                    setText(f.initial)
                    textSize = 15f
                    typeface = Ios.font(context, Ios.Weight.REGULAR)
                    setTextColor(Ios.color(context, R.color.ios_label))
                    setHintTextColor(Ios.color(context, R.color.ios_tertiary_label))
                    background = null
                    isSingleLine = true
                    setPadding(Ios.dp(context, 10f), Ios.dp(context, 9f), Ios.dp(context, 10f), Ios.dp(context, 9f))
                    inputType = if (f.password) {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    } else {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    }
                }
                f.editText = et
                box.addView(et)
            }
            body.addView(box)
        }
        card.addView(body)

        val horizontal = actions.size == 2
        val buttons = LinearLayout(context).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        actions.forEachIndexed { i, action ->
            if (i > 0) {
                buttons.addView(View(context).apply {
                    setBackgroundColor(Ios.color(context, R.color.ios_separator))
                    layoutParams = if (horizontal) {
                        LinearLayout.LayoutParams(Ios.hairline(context), ViewGroup.LayoutParams.MATCH_PARENT)
                    } else {
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ios.hairline(context))
                    }
                })
            }
            val colorRes = if (action.style == Style.DESTRUCTIVE) R.color.ios_red else R.color.ios_blue
            val weight = if (action.style == Style.PREFERRED || action.style == Style.CANCEL) Ios.Weight.SEMIBOLD else Ios.Weight.REGULAR
            buttons.addView(Ios.centered(Ios.text(context, 17f, weight, colorRes, action.title)).apply {
                layoutParams = if (horizontal) {
                    LinearLayout.LayoutParams(0, Ios.dp(context, 44f), 1f)
                } else {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ios.dp(context, 44f))
                }
                background = Ios.cellHighlight(context)
                setOnClickListener {
                    dialog.dismiss()
                    action.handler?.invoke()
                }
            })
        }
        card.addView(Ios.hairlineView(context))
        card.addView(buttons)

        val wrapper = FrameLayout(context)
        wrapper.addView(card, FrameLayout.LayoutParams(Ios.dp(context, 270f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        dialog.setContentView(wrapper)
        dialog.setCancelable(actions.any { it.style == Style.CANCEL })
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (fields.isNotEmpty()) setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        card.alpha = 0f
        card.scaleX = 1.18f
        card.scaleY = 1.18f
        dialog.setOnShowListener {
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
            fields.firstOrNull()?.editText?.let { et ->
                et.requestFocus()
                et.setSelection(et.text.length)
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.show()
        return dialog
    }
}

/**
 * iOS modal sheet with a navigation bar: [Cancel]  Title  [Done].
 * Not draggable, so wheels inside it scroll freely; dismissed via the buttons or a tap outside.
 */
class IosSheet(
    private val context: Context,
    private val title: String,
    private val confirmTitle: String = "Done",
    private val cancelTitle: String = "Cancel"
) {
    private val dialog = BottomSheetDialog(context)
    val content: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, Ios.dp(context, 28f))
    }
    val form = IosForm(context, content, elevated = true)
    private var confirmView: TextView? = null
    var onConfirm: (() -> Boolean)? = null
    var onCancel: (() -> Unit)? = null

    fun setConfirmEnabled(enabled: Boolean) {
        confirmView?.isEnabled = enabled
        confirmView?.alpha = if (enabled) 1f else 0.35f
    }

    fun show(): IosSheet {
        val radius = Ios.dpf(context, 12f)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ios.roundedCorners(Ios.color(context, R.color.ios_sheet), radius, radius, 0f, 0f)
        }
        val nav = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ios.dp(context, 56f))
        }
        nav.addView(Ios.text(context, 17f, Ios.Weight.REGULAR, R.color.ios_blue, cancelTitle).apply {
            setPadding(Ios.dp(context, 16f), Ios.dp(context, 12f), Ios.dp(context, 16f), Ios.dp(context, 12f))
            setOnClickListener {
                onCancel?.invoke()
                dialog.dismiss()
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        nav.addView(Ios.text(context, 17f, Ios.Weight.SEMIBOLD, R.color.ios_label, title),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        confirmView = Ios.text(context, 17f, Ios.Weight.SEMIBOLD, R.color.ios_blue, confirmTitle).apply {
            setPadding(Ios.dp(context, 16f), Ios.dp(context, 12f), Ios.dp(context, 16f), Ios.dp(context, 12f))
            setOnClickListener {
                if (onConfirm?.invoke() != false) dialog.dismiss()
            }
        }
        nav.addView(confirmView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(nav)
        root.addView(ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        })

        dialog.setContentView(root)
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = false
        }
        dialog.setOnCancelListener { onCancel?.invoke() }
        dialog.show()
        return this
    }

    fun dismiss() = dialog.dismiss()
}

/** UIAlertController (.actionSheet) replica: option card plus a separate Cancel card. */
class IosActionSheet(private val context: Context) {

    private class Option(val title: String, val destructive: Boolean, val handler: () -> Unit)

    private var title: String? = null
    private val options = mutableListOf<Option>()

    fun title(value: String) = apply { title = value }
    fun option(title: String, destructive: Boolean = false, handler: () -> Unit) =
        apply { options.add(Option(title, destructive, handler)) }

    fun show() {
        val dialog = Dialog(context, R.style.IosDialog)
        val radius = Ios.dpf(context, 14f)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val m = Ios.dp(context, 8f)
            setPadding(m, 0, m, m)
        }
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ios.rounded(Ios.color(context, R.color.ios_alert), radius)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        title?.let {
            group.addView(Ios.centered(Ios.text(context, 13f, Ios.Weight.SEMIBOLD, R.color.ios_secondary_label, it)).apply {
                setPadding(Ios.dp(context, 16f), Ios.dp(context, 14f), Ios.dp(context, 16f), Ios.dp(context, 14f))
            })
        }
        options.forEachIndexed { i, opt ->
            if (i > 0 || title != null) group.addView(Ios.hairlineView(context))
            group.addView(Ios.centered(Ios.text(context, 19f, Ios.Weight.REGULAR,
                if (opt.destructive) R.color.ios_red else R.color.ios_blue, opt.title)).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ios.dp(context, 56f))
                background = Ios.cellHighlight(context)
                setOnClickListener {
                    dialog.dismiss()
                    opt.handler()
                }
            })
        }
        root.addView(group)
        root.addView(Ios.centered(Ios.text(context, 19f, Ios.Weight.SEMIBOLD, R.color.ios_blue, "Cancel")).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ios.dp(context, 56f)).apply {
                topMargin = Ios.dp(context, 8f)
            }
            background = Ios.cellHighlight(context, Ios.rounded(Ios.color(context, R.color.ios_cell_elevated), radius))
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            setOnClickListener { dialog.dismiss() }
        })
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(R.style.IosActionSheetAnimation)
        }
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }
}
