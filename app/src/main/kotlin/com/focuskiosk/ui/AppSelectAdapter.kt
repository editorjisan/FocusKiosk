package com.focuskiosk.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.focuskiosk.databinding.ItemAppSelectBinding
import com.focuskiosk.launcher.AppInfo

/**
 * AppSelectAdapter
 * ----------------
 * Checkable list adapter for the setup wizard app-selection step.
 * Maintains a mutable selected-packages set; expose via getSelectedPackages().
 */
class AppSelectAdapter : RecyclerView.Adapter<AppSelectAdapter.VH>() {

    private val apps = mutableListOf<AppInfo>()
    private val selected = mutableSetOf<String>()

    fun submitList(list: List<AppInfo>) {
        apps.clear(); apps.addAll(list); notifyDataSetChanged()
    }

    fun getSelectedPackages(): Set<String> = selected.toSet()

    inner class VH(private val b: ItemAppSelectBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(app: AppInfo) {
            b.ivIcon.setImageDrawable(app.icon)
            b.tvName.text = app.label
            b.tvPackage.text = app.packageName
            b.cbSelected.isChecked = app.packageName in selected

            // Toggle on row tap or checkbox tap.
            val toggle = { _: Any ->
                if (app.packageName in selected) selected.remove(app.packageName)
                else selected.add(app.packageName)
                b.cbSelected.isChecked = app.packageName in selected
                Unit
            }
            b.root.setOnClickListener(toggle)
            b.cbSelected.setOnClickListener(toggle)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemAppSelectBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(apps[position])
    override fun getItemCount() = apps.size
}
