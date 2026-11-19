package com.fclglucolink.app.alarm

import java.util.Calendar

/**
 * RONDE 256 — "Niet storen": between a start and an end time of the day, the chosen alarm types
 * do not sound. All other alarms work as always. Times are minutes since midnight in the local
 * time of the phone. A window that crosses midnight (for example 22:00 to 07:00) is supported.
 *
 * Used by [AlarmMonitor.checkOnce]. Settings live in AppSettings (alarmQuiet...).
 */
data class AlarmQuietHours(
    val enabled: Boolean,
    val startMinutes: Int,
    val endMinutes: Int,
    val types: Set<AlarmType>
) {
    /** True when [nowMs] is inside the window and at least one type is chosen. */
    fun isActiveAt(nowMs: Long): Boolean {
        if (!enabled || types.isEmpty() || startMinutes == endMinutes) return false
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val t = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (startMinutes < endMinutes) {
            t >= startMinutes && t < endMinutes
        } else {
            t >= startMinutes || t < endMinutes
        }
    }

    companion object {
        const val DEFAULT_START_MINUTES = 23 * 60
        const val DEFAULT_END_MINUTES = 7 * 60
    }
}
