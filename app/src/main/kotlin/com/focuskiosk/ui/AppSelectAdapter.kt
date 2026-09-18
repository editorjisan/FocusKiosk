package com.focuskiosk.ui

import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.recyclerview.widget.RecyclerView
import com.focuskiosk.databinding.ItemAppSelectBinding
import com.focuskiosk.launcher.AppInfo
import java.util.Locale

/**
 * AppSelectAdapter
 * ----------------
 * Checkable Apple HIG list adapter with real-time search filtering.
 * Maintains persistent package selection state across search queries.
 */
class AppSelectAdapter : RecyclerView.Adapter<AppSelectAdapter.VH>() {

    private val allApps = mutableListOf<AppInfo>()
    private val displayedApps = mutableListOf<AppInfo>()
    private val selected = mutableSetOf<String>()
    private var currentFilterQuery: String = ""

    fun submitList(list: List<AppInfo>) {
        allApps.clear()
        allApps.addAll(list)
        applyFilter()
    }

    fun filter(query: CharSequence) {
        currentFilterQuery = query.toString().trim().lowercase(Locale.ROOT)
        applyFilter()
    }

    private fun applyFilter() {
        displayedApps.clear()
        if (currentFilterQuery.isEmpty()) {
            displayedApps.addAll(allApps)
        } else {
            for (app in allApps) {
                if (app.label.lowercase(Locale.ROOT).contains(currentFilterQuery) ||
                    app.packageName.lowercase(Locale.ROOT).contains(currentFilterQuery)) {
                    displayedApps.add(app)
                }
            }
        }
        notifyDataSetChanged()
    }

    fun getSelectedPackages(): Set<String> = selected.toSet()

    inner class VH(private val b: ItemAppSelectBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            // Apply Apple squircle rounded corners to app icon
            b.ivIcon.clipToOutline = true
            b.ivIcon.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radius = 10f * view.resources.displayMetrics.density
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }

        fun bind(app: AppInfo) {
            b.ivIcon.setImageDrawable(app.icon)
            b.tvName.text = app.label
            b.tvPackage.text = app.packageName
            b.cbSelected.isChecked = app.packageName in selected

            val toggle = { _: Any ->
                if (app.packageName in selected) {
                    selected.remove(app.packageName)
                } else {
                    selected.add(app.packageName)
                }
                b.cbSelected.isChecked = app.packageName in selected
                Unit
            }
            b.root.setOnClickListener(toggle)
            b.cbSelected.setOnClickListener(toggle)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemAppSelectBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(displayedApps[position])
    override fun getItemCount() = displayedApps.size
}
