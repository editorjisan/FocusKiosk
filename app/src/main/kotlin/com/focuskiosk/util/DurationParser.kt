package com.focuskiosk.util

import java.util.Calendar

/**
 * DurationParser
 * ──────────────
 * Parses human-friendly duration strings into a future epoch timestamp.
 *
 * Supported format (all fields optional, but at least one required):
 *   [Ny][Nm][Nd]   e.g.  30d  |  6m  |  1y  |  1y6m  |  2y3m15d
 *
 * Uses Calendar.add() so months/years are calendar-accurate (leap years,
 * varying month lengths are handled automatically).
 */
object DurationParser {

    // Matches any combination of optional year/month/day tokens.
    private val PATTERN = Regex("""^(?:(\d+)y)?(?:(\d+)m)?(?:(\d+)d)?$""")

    /**
     * Parses [input] and returns the corresponding future Unix timestamp
     * in milliseconds, or null if the input is empty or invalid.
     */
    fun parseToFutureTimestampMs(input: String): Long? {
        val trimmed = input.trim().lowercase()
        if (trimmed.isBlank()) return null

        val match = PATTERN.matchEntire(trimmed) ?: return null

        val years  = match.groupValues[1].toIntOrNull() ?: 0
        val months = match.groupValues[2].toIntOrNull() ?: 0
        val days   = match.groupValues[3].toIntOrNull() ?: 0

        if (years == 0 && months == 0 && days == 0) return null

        val cal = Calendar.getInstance()
        if (years  > 0) cal.add(Calendar.YEAR,         years)
        if (months > 0) cal.add(Calendar.MONTH,        months)
        if (days   > 0) cal.add(Calendar.DAY_OF_MONTH, days)

        return cal.timeInMillis
    }

    /** Formats a remaining-millisecond value as "Xd Xh Xm" or "Xm Xs". */
    fun formatRemaining(remainingMs: Long): String {
        if (remainingMs <= 0) return "Unlocked"
        val days    = remainingMs / (1000L * 60 * 60 * 24)
        val hours   = (remainingMs % (1000L * 60 * 60 * 24)) / (1000L * 60 * 60)
        val minutes = (remainingMs % (1000L * 60 * 60)) / (1000L * 60)
        val seconds = (remainingMs % (1000L * 60)) / 1000L
        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0) append("${hours}h ")
            if (days == 0L && hours == 0L) {
                append("${minutes}m ${seconds}s")
            } else {
                append("${minutes}m")
            }
        }.trim()
    }
}
