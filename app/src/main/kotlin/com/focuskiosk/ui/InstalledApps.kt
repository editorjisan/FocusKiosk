package com.focuskiosk.ui

import android.content.Context
import android.content.Intent
import com.focuskiosk.launcher.AppInfo

/** Launchable apps on the device (one IPC call), cached for the process lifetime. */
object InstalledApps {

    @Volatile
    private var cache: List<AppInfo>? = null

    fun cached(): List<AppInfo>? = cache

    fun load(context: Context, refresh: Boolean = false): List<AppInfo> {
        if (!refresh) cache?.let { return it }
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = runCatching { pm.queryIntentActivities(launchIntent, 0) }.getOrElse { emptyList() }
        val seen = HashSet<String>()
        val list = ArrayList<AppInfo>(resolveInfos.size + 1)
        for (resolve in resolveInfos) {
            val pkg = resolve.activityInfo?.packageName ?: continue
            if (pkg == context.packageName || !seen.add(pkg)) continue
            runCatching { list.add(AppInfo(pkg, resolve.loadLabel(pm).toString(), resolve.loadIcon(pm))) }
        }
        // The Google app has no launcher entry on some builds but users still expect to allow it.
        val google = "com.google.android.googlequicksearchbox"
        if (google !in seen) runCatching {
            val info = pm.getApplicationInfo(google, 0)
            list.add(AppInfo(google, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info)))
        }
        list.sortBy { it.label.lowercase() }
        cache = list
        return list
    }
}
