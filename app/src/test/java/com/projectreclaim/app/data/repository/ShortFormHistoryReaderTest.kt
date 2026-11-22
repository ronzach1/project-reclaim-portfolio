package com.projectreclaim.app.data.repository

import com.projectreclaim.app.AppUsage
import com.projectreclaim.app.AppUsageReader
import com.projectreclaim.app.CalendarDayUsageTime
import com.projectreclaim.app.TIKTOK_PACKAGE_NAME
import com.projectreclaim.app.UsageTimeRange
import com.projectreclaim.app.data.local.UsageSessionEntity
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortFormHistoryReaderTest {
    @Test
    fun `reads eight days with today TikTok and incomplete historical combined totals`() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-08-25T08:15:00Z"), ZoneId.of("Asia/Jerusalem"))
        val store = FixedDurationHistoryStore()
        val appUsageReader = FixedTikTokUsageReader()

        val history = ShortFormHistoryReader(store, appUsageReader).read(clock)

        assertEquals(8, history.size)
        assertEquals(LocalDate.of(2026, 8, 25), history.first().date)
        assertEquals(LocalDate.of(2026, 8, 18), history.last().date)

        val today = history.first()
        assertEquals(10L, today.instagramReelsDurationMillis)
        assertEquals(30L, today.tikTokDurationMillis)
        assertEquals(20L, today.youTubeShortsDurationMillis)
        assertEquals(60L, today.combinedShortFormDurationMillis)

        history.drop(1).forEach { day ->
            assertEquals(10L, day.instagramReelsDurationMillis)
            assertEquals(20L, day.youTubeShortsDurationMillis)
            assertNull(day.tikTokDurationMillis)
            assertNull(day.combinedShortFormDurationMillis)
        }
        assertEquals(8, store.requestedPlatforms.count { it == "instagram" to "reels" })
        assertEquals(8, store.requestedPlatforms.count { it == "youtube" to "shorts" })
        assertEquals(1, appUsageReader.requestedRanges.size)
        assertEquals(
            CalendarDayUsageTime.history(clock).first().range,
            appUsageReader.requestedRanges.single(),
        )
    }

    @Test
    fun `genuine zero TikTok today remains distinct from unavailable history`() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-08-25T08:15:00Z"), ZoneId.of("Asia/Jerusalem"))

        val history = ShortFormHistoryReader(
            FixedDurationHistoryStore(),
            FixedTikTokUsageReader(tikTokDurationMillis = 0L),
        ).read(clock)

        assertEquals(0L, history.first().tikTokDurationMillis)
        assertEquals(30L, history.first().combinedShortFormDurationMillis)
        history.drop(1).forEach { day ->
            assertNull(day.tikTokDurationMillis)
            assertNull(day.combinedShortFormDurationMillis)
        }
    }

    private class FixedDurationHistoryStore : UsageSessionHistoryStore {
        val requestedPlatforms = mutableListOf<Pair<String, String>>()

        override suspend fun getSessionsOverlapping(
            platform: String,
            usageType: String,
            rangeStartMillis: Long,
            rangeEndMillis: Long,
        ): List<UsageSessionEntity> {
            requestedPlatforms += platform to usageType
            val duration = if (platform == "instagram") 10L else 20L
            return listOf(
                UsageSessionEntity(
                    platform = platform,
                    usageType = usageType,
                    startTimeMillis = rangeStartMillis,
                    endTimeMillis = rangeStartMillis + duration,
                ),
            )
        }
    }

    private class FixedTikTokUsageReader(
        private val tikTokDurationMillis: Long = 30L,
    ) : AppUsageReader {
        val requestedRanges = mutableListOf<UsageTimeRange>()

        override fun readUsage(range: UsageTimeRange, zone: ZoneId): List<AppUsage> {
            requestedRanges += range
            return listOf(
                AppUsage(
                    packageName = TIKTOK_PACKAGE_NAME,
                    appName = "TikTok",
                    durationMillis = tikTokDurationMillis,
                ),
            )
        }
    }
}
