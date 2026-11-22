package com.projectreclaim.app

/** Formats local tracked-session durations with seconds for short validation sessions. */
object TrackedUsageDurationFormatter {
    fun format(durationMillis: Long): String {
        val totalSeconds = durationMillis.coerceAtLeast(0L) / SECONDS_TO_MILLIS
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE

        return buildList {
            if (hours > 0) add("${hours}h")
            if (minutes > 0) add("${minutes}m")
            if (seconds > 0 || isEmpty()) add("${seconds}s")
        }.joinToString(" ")
    }

    private const val SECONDS_TO_MILLIS = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE
}
