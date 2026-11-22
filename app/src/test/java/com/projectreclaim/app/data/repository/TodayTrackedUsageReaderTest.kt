package com.projectreclaim.app.data.repository

import com.projectreclaim.app.TodayUsageTime
import com.projectreclaim.app.data.local.UsageSessionEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TodayTrackedUsageReaderTest {
    @Test
    fun `queries requested type and exactly todays range then returns clipped total`() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-08-25T08:15:00Z"), ZoneId.of("Asia/Jerusalem"))
        val expectedRange = TodayUsageTime.range(clock)
        val store = FakeUsageSessionHistoryStore(
            sessions = listOf(
                session(platform = "instagram", usageType = "reels", start = expectedRange.startMillis - 10, end = expectedRange.startMillis + 20),
                session(platform = "instagram", usageType = "reels", start = expectedRange.endMillis - 30, end = null),
                session(platform = "instagram", usageType = "stories", start = expectedRange.startMillis, end = expectedRange.endMillis),
                session(platform = "tiktok", usageType = "reels", start = expectedRange.startMillis, end = expectedRange.endMillis),
            ),
        )

        val duration = TodayTrackedUsageReader(store).readDurationMillis(
            platform = "instagram",
            usageType = "reels",
            clock = clock,
        )

        assertEquals(50L, duration)
        assertEquals("instagram", store.requestedPlatform)
        assertEquals("reels", store.requestedUsageType)
        assertEquals(expectedRange.startMillis, store.requestedRangeStartMillis)
        assertEquals(expectedRange.endMillis, store.requestedRangeEndMillis)
    }

    private class FakeUsageSessionHistoryStore(
        private val sessions: List<UsageSessionEntity>,
    ) : UsageSessionHistoryStore {
        var requestedPlatform: String? = null
        var requestedUsageType: String? = null
        var requestedRangeStartMillis: Long? = null
        var requestedRangeEndMillis: Long? = null

        override suspend fun getSessionsOverlapping(
            platform: String,
            usageType: String,
            rangeStartMillis: Long,
            rangeEndMillis: Long,
        ): List<UsageSessionEntity> {
            requestedPlatform = platform
            requestedUsageType = usageType
            requestedRangeStartMillis = rangeStartMillis
            requestedRangeEndMillis = rangeEndMillis
            return sessions.filter { it.platform == platform && it.usageType == usageType }
        }
    }

    private fun session(platform: String, usageType: String, start: Long, end: Long?) = UsageSessionEntity(
        platform = platform,
        usageType = usageType,
        startTimeMillis = start,
        endTimeMillis = end,
    )
}
