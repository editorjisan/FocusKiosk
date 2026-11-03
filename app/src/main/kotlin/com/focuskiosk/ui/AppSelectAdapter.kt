package com.focuskiosk.ui

import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.focuskiosk.R
import com.focuskiosk.launcher.AppInfo
import com.focuskiosk.ui.ios.Ios
import java.util.Locale

/**
 * AppSelectAdapter
 * ----------------
 * Inset-grouped, checkmark list of apps (iOS style) with live search.
 * Apps allowed when the screen opened are grouped first so they are easy to review.
 */
class AppSelectAdapter(
    private val onSelectionChanged: ((count: Int) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Item {
        data class Header(val title: String) : Item()
        data class App(val info: AppInfo, val first: Boolean, val last: Boolean) : Item()
        object Empty : Item()
    }

    private val allApps = mutableListOf<AppInfo>()
    private val selected = mutableSetOf<String>()
    private var pinnedFirst = emptySet<String>()
    private var query = ""
    private var items = listOf<Item>()

    fun submitList(list: List<AppInfo>) {
        allApps.clear()
        allApps.addAll(list)
        rebuild()
    }

    fun setSelectedPackages(packages: Set<String>, regroup: Boolean = false) {
        selected.clear()
        selected.addAll(packages)
        if (regroup) pinnedFirst = packages.toSet()
        rebuild()
        onSelectionChanged?.invoke(selected.size)
    }

    fun getSelectedPackages(): Set<String> = selected.toSet()

    fun filter(text: CharSequence) {
        query = text.toString().trim().lowercase(Locale.ROOT)
        rebuild()
    }

    val visibleAppCount: Int get() = items.count { it is Item.App }

    private fun rebuild() {
        val matches = allApps.filter {
            query.isEmpty() || it.label.lowercase(Locale.ROOT).contains(query) || it.packageName.lowercase(Locale.ROOT).contains(query)
        }
        val out = mutableListOf<Item>()
        fun group(title: String, apps: List<AppInfo>) {
            if (apps.isEmpty()) return
            out.add(Item.Header(title))
            apps.forEachIndexed { i, app -> out.add(Item.App(app, i == 0, i == apps.lastIndex)) }
        }
        val (allowed, others) = matches.partition { it.packageName in pinnedFirst }
        group("Allowed", allowed)
        group(if (allowed.isEmpty()) "Apps" else "Other Apps", others)
        if (out.isEmpty()) out.add(Item.Empty)
        items = out
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is Item.Header -> 0
        is Item.App -> 1
        Item.Empty -> 2
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return when (viewType) {
            0 -> object : RecyclerView.ViewHolder(
                Ios.text(ctx, 13f, Ios.Weight.REGULAR, R.color.ios_secondary_label).apply {
                    letterSpacing = 0.02f
                    setPadding(Ios.dp(ctx, 32f), Ios.dp(ctx, 22f), Ios.dp(ctx, 32f), Ios.dp(ctx, 7f))
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            ) {}
            2 -> object : RecyclerView.ViewHolder(
                Ios.centered(Ios.text(ctx, 17f, Ios.Weight.REGULAR, R.color.ios_secondary_label, "No Results")).apply {
                    setPadding(0, Ios.dp(ctx, 80f), 0, 0)
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            ) {}
            else -> AppVH(parent)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder.itemView as TextView).text = item.title.uppercase()
            is Item.App -> (holder as AppVH).bind(item)
            Item.Empty -> Unit
        }
    }

    private inner class AppVH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
    ) {
        private val ctx = parent.context
        private val wrapper = itemView as LinearLayout
        private val separator = Ios.hairlineView(ctx).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = Ios.dp(ctx, 62f)
        }
        private val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ios.dp(ctx, 52f)
            setPadding(Ios.dp(ctx, 16f), Ios.dp(ctx, 8f), Ios.dp(ctx, 16f), Ios.dp(ctx, 8f))
        }
        private val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(Ios.dp(ctx, 32f), Ios.dp(ctx, 32f)).apply { marginEnd = Ios.dp(ctx, 14f) }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.225f)
                }
            }
        }
        private val name = Ios.text(ctx, 17f).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        private val check = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_ios_checkmark)
            layoutParams = LinearLayout.LayoutParams(Ios.dp(ctx, 20f), Ios.dp(ctx, 20f)).apply { marginStart = Ios.dp(ctx, 10f) }
        }

        init {
            wrapper.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val side = Ios.dp(ctx, 16f)
                setMargins(side, 0, side, 0)
            }
            row.addView(icon)
            row.addView(name)
            row.addView(check)
            wrapper.addView(separator)
            wrapper.addView(row)
        }

        fun bind(item: Item.App) {
            val r = Ios.dpf(ctx, 10f)
            val cell = Ios.color(ctx, R.color.ios_cell)
            wrapper.background = Ios.roundedCorners(cell, if (item.first) r else 0f, if (item.first) r else 0f, if (item.last) r else 0f, if (item.last) r else 0f)
            wrapper.clipToOutline = true
            wrapper.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            row.background = Ios.cellHighlight(ctx)
            separator.visibility = if (item.first) android.view.View.GONE else android.view.View.VISIBLE
            icon.setImageDrawable(item.info.icon)
            name.text = item.info.label
            check.visibility = if (item.info.packageName in selected) android.view.View.VISIBLE else android.view.View.INVISIBLE
            row.setOnClickListener {
                val pkg = item.info.packageName
                if (!selected.remove(pkg)) selected.add(pkg)
                Ios.tick(row)
                check.visibility = if (pkg in selected) android.view.View.VISIBLE else android.view.View.INVISIBLE
                onSelectionChanged?.invoke(selected.size)
            }
        }
    }
}
