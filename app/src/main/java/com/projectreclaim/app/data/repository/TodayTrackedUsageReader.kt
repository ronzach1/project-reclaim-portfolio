package com.projectreclaim.app.data.repository

import com.projectreclaim.app.TodayUsageTime
import com.projectreclaim.app.UsageTimeRange
import java.time.Clock

/** Reads tracked duration for one platform and usage type within a supplied range. */
class TrackedUsageReader(private val usageSessionHistoryStore: UsageSessionHistoryStore) {
    suspend fun readDurationMillis(
        platform: String,
        usageType: String,
        range: UsageTimeRange,
    ): Long {
        val sessions = usageSessionHistoryStore.getSessionsOverlapping(
            platform = platform,
            usageType = usageType,
            rangeStartMillis = range.startMillis,
            rangeEndMillis = range.endMillis,
        )
        return UsageSessionDurationCalculator.calculate(sessions, range)
    }
}

/** Reads the current local day's tracked duration for one platform and usage type. */
class TodayTrackedUsageReader(private val usageSessionHistoryStore: UsageSessionHistoryStore) {
    suspend fun readDurationMillis(
        platform: String,
        usageType: String,
        clock: Clock = Clock.systemDefaultZone(),
    ): Long {
        val range = TodayUsageTime.range(clock)
        return TrackedUsageReader(usageSessionHistoryStore).readDurationMillis(
            platform = platform,
            usageType = usageType,
            range = range,
        )
    }
}
