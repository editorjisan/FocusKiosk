package com.focuskiosk.launcher

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.focuskiosk.databinding.ItemAppBinding

/**
 * AppGridAdapter
 * ──────────────
 * RecyclerView adapter powering the home launcher icon grid.
 * Uses ListAdapter + DiffUtil for efficient, flicker-free updates.
 */
class AppGridAdapter(
    private val onAppClick: (AppInfo) -> Unit
) : ListAdapter<AppInfo, AppGridAdapter.AppViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(old: AppInfo, new: AppInfo) =
                old.packageName == new.packageName
            override fun areContentsTheSame(old: AppInfo, new: AppInfo) =
                old.label == new.label
        }
    }

    inner class AppViewHolder(private val b: ItemAppBinding)
        : RecyclerView.ViewHolder(b.root) {

        fun bind(app: AppInfo) {
            b.ivAppIcon.setImageDrawable(app.icon)
            b.tvAppName.text = app.label
            b.root.setOnClickListener { onAppClick(app) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        AppViewHolder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) =
        holder.bind(getItem(position))
}
