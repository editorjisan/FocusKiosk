package com.focuskiosk.launcher

import android.graphics.drawable.Drawable

/** Lightweight value class representing one launchable whitelisted app. */
data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable
)
