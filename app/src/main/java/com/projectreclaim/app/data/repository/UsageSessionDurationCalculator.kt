package com.projectreclaim.app.data.repository

import com.projectreclaim.app.UsageTimeRange
import com.projectreclaim.app.data.local.UsageSessionEntity

/** Calculates the total portion of sessions that falls within a half-open time range. */
object UsageSessionDurationCalculator {
    fun calculate(
        sessions: List<UsageSessionEntity>,
        range: UsageTimeRange,
    ): Long = sessions.sumOf { session ->
        val effectiveStart = maxOf(session.startTimeMillis, range.startMillis)
        val effectiveEnd = minOf(session.endTimeMillis ?: range.endMillis, range.endMillis)
        (effectiveEnd - effectiveStart).coerceAtLeast(0L)
    }
}
