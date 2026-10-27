package com.focuskiosk.alarm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Model representing an individual alarm item.
 */
data class AlarmItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val hour: Int,
    val minute: Int,
    val label: String = "Wake up",
    val isEnabled: Boolean = true
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("label", label)
        put("isEnabled", isEnabled)
    }

    companion object {
        fun fromJsonObject(json: JSONObject): AlarmItem {
            return AlarmItem(
                id = json.optString("id", java.util.UUID.randomUUID().toString()),
                hour = json.getInt("hour"),
                minute = json.getInt("minute"),
                label = json.optString("label", "Wake up"),
                isEnabled = json.optBoolean("isEnabled", true)
            )
        }

        fun listToJsonString(list: List<AlarmItem>): String {
            val array = JSONArray()
            for (item in list) {
                array.put(item.toJsonObject())
            }
            return array.toString()
        }

        fun listFromJsonString(jsonStr: String): List<AlarmItem> {
            if (jsonStr.isBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<AlarmItem>()
                for (i in 0 until array.length()) {
                    list.add(fromJsonObject(array.getJSONObject(i)))
                }
                list
            }.getOrDefault(emptyList())
        }
    }
}
