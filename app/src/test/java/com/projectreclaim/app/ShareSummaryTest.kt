package com.projectreclaim.app

import com.projectreclaim.app.data.local.UsageSessionEntity
import com.projectreclaim.app.data.repository.UsageSessionHistoryStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareSummaryTest {
    private val zone = ZoneId.of("America/New_York")
    private val clock = Clock.fixed(Instant.parse("2026-03-10T04:30:00Z"), zone)
    private val today = LocalDate.now(clock)

    @Test
    fun `week uses raw sums across the same three eligible days including DST`() = runBlocking {
        val start = today.minusDays(3).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val expectedRange = UsageTimeRange(start, end)
        val trackRanges = mutableListOf<UsageTimeRange>()
        val store = object : UsageSessionHistoryStore {
            override suspend fun getSessionsOverlapping(
                platform: String,
                usageType: String,
                rangeStartMillis: Long,
                rangeEndMillis: Long,
            ): List<UsageSessionEntity> {
                trackRanges += UsageTimeRange(rangeStartMillis, rangeEndMillis)
                val split = if (platform == "instagram") 12_333L else 9_333L
                return listOf(
                    UsageSessionEntity(
                        platform = platform, usageType = usageType,
                        startTimeMillis = start - 1_000, endTimeMillis = start + split,
                    ),
                    UsageSessionEntity(
                        platform = platform, usageType = usageType,
                        startTimeMillis = end, endTimeMillis = end + 60_000,
                    ),
                )
            }
        }
        val aggregates = object : AggregatedAppUsageReader {
            override fun readAggregatedUsage(range: UsageTimeRange): AggregatedAppUsageReadResult {
                assertEquals(expectedRange, range)
                return AggregatedAppUsageReadResult.Available(
                    mapOf(TIKTOK_PACKAGE_NAME to 9_334L, FACEBOOK_PACKAGE_NAME to 99_000L),
                )
            }
        }
        val reader = PreviousSevenDaysUsageReader(store, aggregates)
        // Dividing by three first would truncate 31,000 ms to 30,999 ms on reconstruction.
        val summary = ShareSummary.week(setup(4), true, clock, reader::readTotals)
        assertEquals(71, Duration.ofMillis(end - start).toHours().toInt())
        assertEquals(listOf(expectedRange, expectedRange), trackRanges)
        assertEquals("3 eligible completed days", summary.scope)
        assertEquals("Mar 7, 2026 – Mar 9, 2026", summary.dateLabel)
        assertEquals(31_000L, summary.totalMillis)
        assertEquals("31s", summary.totalLabel)
        assertEquals(listOf("12s", "9s", "9s"), summary.breakdown.map { it.second })
    }

    @Test
    fun `week excludes setup and today and caps older coverage at seven days`() = runBlocking {
        val tooSoon = ShareSummary.week(setup(1), true, clock) { error("No eligible days") }
        assertEquals("0 eligible completed days", tooSoon.scope)
        assertNull(tooSoon.totalMillis)
        val week = ShareSummary.week(setup(20), true, clock) {
            assertEquals(today.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli(), it.startMillis)
            CompletedDaysUsageTotals(0L, 0L, emptyMap())
        }
        assertEquals("7 eligible completed days", week.scope)
        assertEquals("Mar 3, 2026 – Mar 9, 2026", week.dateLabel)
        assertEquals("0s", week.totalLabel)
    }

    @Test
    fun `week with no setup or Usage Access does not query sources`() = runBlocking {
        val noSetup = ShareSummary.week(null, false, clock) { error("No setup") }
        assertTrue(noSetup.status.contains("setup needed"))
        val noAccess = ShareSummary.week(setup(2), false, clock) { error("Permission denied") }
        assertEquals("1 eligible completed day", noAccess.scope)
        assertEquals("Mar 9, 2026", noAccess.dateLabel)
        assertEquals("Usage Access needed for TikTok", noAccess.status)
        assertNull(noAccess.totalMillis)
    }

    @Test
    fun `week retains coverage metadata when aggregate data is unavailable`() = runBlocking {
        val summary = ShareSummary.week(setup(4), true, clock) { null }
        assertEquals("3 eligible completed days", summary.scope)
        assertEquals("Unavailable", summary.totalLabel)
        assertTrue(summary.breakdown.all { it.second == "Unavailable" })
    }

    @Test
    fun `Today keeps recorded values and labels paused tracking as partial`() {
        val summary = ShareSummary.today(
            today, TrackedUsageState.Available(1_990), TrackedUsageState.Available(1_990),
            TikTokTodayState.Available(1_020), accessibilityEnabled = false,
        )
        assertEquals("Mar 10, 2026", summary.dateLabel)
        assertEquals(5_000L, summary.totalMillis)
        assertEquals("5s", summary.totalLabel)
        assertTrue(summary.status.startsWith("Partial"))
        assertEquals(listOf("1s", "1s", "1s"), summary.breakdown.map { it.second })
    }

    @Test
    fun `Today withholds total when permission or any source is unavailable`() {
        for (tiktok in listOf(TikTokTodayState.PermissionRequired, TikTokTodayState.Unavailable)) {
            val summary = ShareSummary.today(
                today, TrackedUsageState.Available(2_000), TrackedUsageState.Unavailable,
                tiktok, accessibilityEnabled = true,
            )
            assertNull(summary.totalMillis)
            assertEquals(listOf("2s", "Unavailable", "Unavailable"), summary.breakdown.map { it.second })
        }
    }

    private fun setup(daysAgo: Long): Long =
        today.minusDays(daysAgo).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
}
