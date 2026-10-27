package com.focuskiosk.ui

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

    fun showSavePresetDialog(context: Context, selectedPackages: Set<String>, onSaved: (String) -> Unit) {
        if (selectedPackages.isEmpty()) {
            Toast.makeText(context, "Select at least one app before saving a preset!", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(context).apply {
            hint = "Preset Name (e.g. Work, Study, Video Upload)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8E8E93"))
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(context)
            .setTitle("💾 Save App Preset")
            .setMessage("Save currently selected ${selectedPackages.size} apps as a quick preset:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    savePreset(context, name, selectedPackages)
                    Toast.makeText(context, "Preset '$name' saved! (${selectedPackages.size} apps)", Toast.LENGTH_SHORT).show()
                    onSaved(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showLoadPresetDialog(context: Context, onPresetSelected: (Set<String>) -> Unit) {
        val presets = getPresets(context)
        if (presets.isEmpty()) {
            Toast.makeText(context, "No saved presets found. Select apps and tap 'Save Preset' first!", Toast.LENGTH_LONG).show()
            return
        }

        val presetNames = presets.keys.toTypedArray()
        val displayNames = presetNames.map { "$it (${presets[it]?.size ?: 0} apps)" }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("📂 Load App Preset")
            .setItems(displayNames) { _, which ->
                val chosenName = presetNames[which]
                val pkgs = presets[chosenName] ?: emptySet()
                onPresetSelected(pkgs)
                Toast.makeText(context, "Loaded preset '$chosenName' (${pkgs.size} apps)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
