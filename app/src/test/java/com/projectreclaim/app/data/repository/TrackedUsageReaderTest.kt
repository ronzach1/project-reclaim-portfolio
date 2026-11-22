package com.projectreclaim.app.data.repository

import com.projectreclaim.app.CalendarDayUsageTime
import com.projectreclaim.app.data.local.UsageSessionEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackedUsageReaderTest {
    @Test
    fun `historical range clips sessions crossing both calendar boundaries`() = runBlocking {
        val range = CalendarDayUsageTime.history(
            Clock.fixed(Instant.parse("2026-08-25T08:15:00Z"), ZoneId.of("Asia/Jerusalem")),
        )[1].range
        val store = RecordingHistoryStore(
            sessions = listOf(
                session(start = range.startMillis - 10L, end = range.startMillis + 20L),
                session(start = range.endMillis - 15L, end = range.endMillis + 10L),
            ),
        )

        val duration = TrackedUsageReader(store).readDurationMillis(
            platform = "youtube",
            usageType = "shorts",
            range = range,
        )

        assertEquals(35L, duration)
        assertEquals("youtube", store.requestedPlatform)
        assertEquals("shorts", store.requestedUsageType)
        assertEquals(range.startMillis, store.requestedRangeStartMillis)
        assertEquals(range.endMillis, store.requestedRangeEndMillis)
    }

    private class RecordingHistoryStore(
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
            return sessions
        }
    }

    private fun session(start: Long, end: Long?) = UsageSessionEntity(
        platform = "youtube",
        usageType = "shorts",
        startTimeMillis = start,
        endTimeMillis = end,
    )
}
