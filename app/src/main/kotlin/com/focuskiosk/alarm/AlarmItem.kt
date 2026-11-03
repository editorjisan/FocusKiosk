package com.focuskiosk.alarm

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Model representing an individual alarm item.
 *
 * [days] holds Calendar.DAY_OF_WEEK values (SUNDAY=1 … SATURDAY=7).
 * An empty set means the alarm rings once and then switches itself off (iOS "Never").
 */
data class AlarmItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val hour: Int,
    val minute: Int,
    val label: String = DEFAULT_LABEL,
    val isEnabled: Boolean = true,
    val days: Set<Int> = emptySet()
) {
    val isRepeating: Boolean get() = days.isNotEmpty()

    fun repeatSummary(): String = summarize(days)

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("label", label)
        put("isEnabled", isEnabled)
        put("days", JSONArray().apply { days.sorted().forEach { put(it) } })
    }

    companion object {
        const val DEFAULT_LABEL = "Alarm"
        val EVERY_DAY = (Calendar.SUNDAY..Calendar.SATURDAY).toSet()
        private val WEEKDAYS = (Calendar.MONDAY..Calendar.FRIDAY).toSet()
        private val WEEKENDS = setOf(Calendar.SATURDAY, Calendar.SUNDAY)
        private val SHORT = mapOf(1 to "Sun", 2 to "Mon", 3 to "Tue", 4 to "Wed", 5 to "Thu", 6 to "Fri", 7 to "Sat")
        private val LONG = mapOf(1 to "Sunday", 2 to "Monday", 3 to "Tuesday", 4 to "Wednesday", 5 to "Thursday", 6 to "Friday", 7 to "Saturday")

        fun summarize(days: Set<Int>): String = when {
            days.isEmpty() -> "Never"
            days == EVERY_DAY -> "Every day"
            days == WEEKDAYS -> "Weekdays"
            days == WEEKENDS -> "Weekends"
            days.size == 1 -> "Every ${LONG[days.first()]}"
            else -> days.sorted().joinToString(" ") { SHORT[it].orEmpty() }
        }

        fun fromJsonObject(json: JSONObject): AlarmItem {
            // Alarms saved before v1.5.0 had no repeat days and were re-armed daily.
            val days = json.optJSONArray("days")?.let { arr ->
                (0 until arr.length()).map { arr.getInt(it) }.filter { it in 1..7 }.toSet()
            } ?: EVERY_DAY
            return AlarmItem(
                id = json.optString("id", java.util.UUID.randomUUID().toString()),
                hour = json.getInt("hour"),
                minute = json.getInt("minute"),
                label = json.optString("label", DEFAULT_LABEL).ifBlank { DEFAULT_LABEL },
                isEnabled = json.optBoolean("isEnabled", true),
                days = days
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
