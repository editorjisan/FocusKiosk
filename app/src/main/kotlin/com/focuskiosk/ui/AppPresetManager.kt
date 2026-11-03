package com.focuskiosk.ui

import android.content.Context
import com.focuskiosk.storage.SecureStorage
import org.json.JSONObject

/**
 * AppPresetManager
 * Helper to save, load, and manage named app whitelist presets.
 */
object AppPresetManager {

    fun getPresets(context: Context): Map<String, Set<String>> {
        val raw = SecureStorage.getString(context, SecureStorage.KEY_SAVED_APP_PRESETS_JSON, "")
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, Set<String>>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val pkgArray = json.getJSONArray(name)
                val set = mutableSetOf<String>()
                for (i in 0 until pkgArray.length()) {
                    set.add(pkgArray.getString(i))
                }
                map[name] = set
            }
            map
        }.getOrDefault(emptyMap())
    }

    fun savePreset(context: Context, name: String, packages: Set<String>) {
        val current = getPresets(context).toMutableMap()
        current[name.trim()] = packages
        persistPresets(context, current)
    }

    fun deletePreset(context: Context, name: String) {
        val current = getPresets(context).toMutableMap()
        current.remove(name)
        persistPresets(context, current)
    }

    private fun persistPresets(context: Context, map: Map<String, Set<String>>) {
        val json = JSONObject()
        for ((name, set) in map) {
            val arr = org.json.JSONArray()
            for (pkg in set) {
                arr.put(pkg)
            }
            json.put(name, arr)
        }
        SecureStorage.putString(context, SecureStorage.KEY_SAVED_APP_PRESETS_JSON, json.toString())
    }
}
