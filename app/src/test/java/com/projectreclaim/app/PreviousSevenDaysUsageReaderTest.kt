package com.projectreclaim.app

import com.projectreclaim.app.data.local.UsageSessionEntity
import com.projectreclaim.app.data.repository.UsageSessionHistoryStore
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviousSevenDaysUsageReaderTest {
    @Test
    fun `uses identical covered range and dynamic denominator before day seven`() = runBlocking {
        val store = FixedTrackedUsageStore(28_000L, 35_000L)
        val aggregatedReader = FakeAggregatedAppUsageReader(
            AggregatedAppUsageReadResult.Available(
                mapOf(
                    INSTAGRAM_PACKAGE_NAME to 7_000L,
                    TIKTOK_PACKAGE_NAME to 14_000L,
                    FACEBOOK_PACKAGE_NAME to 21_000L,
                    X_PACKAGE_NAME to 28_000L,
                    "unrelated.package" to 700_000L,
                ),
            ),
        )
        val setupStartedAt = setupStartedAt(LocalDate.of(2026, 8, 20))

        val result = PreviousSevenDaysUsageReader(store, aggregatedReader).read(setupStartedAt, TEST_CLOCK)

        assertEquals(
            PreviousSevenDaysUsageResult.Available(
                PreviousSevenDaysAverages(19_250L, 17_500L),
                dayCount = 4,
            ),
            result,
        )
        val expectedCoverage = PreviousSevenDaysUsageTime.coverage(setupStartedAt, TEST_CLOCK)
        assertEquals(expectedCoverage.range, aggregatedReader.requestedRange)
        assertEquals(listOf(expectedCoverage.range, expectedCoverage.range), store.requestedRanges)
    }

    @Test
    fun `caps calculation at seven covered completed days`() = runBlocking {
        val result = PreviousSevenDaysUsageReader(
            FixedTrackedUsageStore(28_000L, 35_000L),
            FakeAggregatedAppUsageReader(
                AggregatedAppUsageReadResult.Available(
                    mapOf(
                        INSTAGRAM_PACKAGE_NAME to 7_000L,
                        TIKTOK_PACKAGE_NAME to 14_000L,
                        FACEBOOK_PACKAGE_NAME to 21_000L,
                        X_PACKAGE_NAME to 28_000L,
                    ),
                ),
            ),
        ).read(setupStartedAt(LocalDate.of(2026, 8, 10)), TEST_CLOCK)

        assertEquals(
            PreviousSevenDaysUsageResult.Available(
                PreviousSevenDaysAverages(11_000L, 10_000L),
                dayCount = 7,
            ),
            result,
        )
    }

    @Test
    fun `partial setup day does not calculate an average`() = runBlocking {
        val store = FixedTrackedUsageStore(28_000L, 35_000L)
        val aggregatedReader = FakeAggregatedAppUsageReader(
            AggregatedAppUsageReadResult.Available(emptyMap()),
        )

        val result = PreviousSevenDaysUsageReader(store, aggregatedReader).read(
            setupStartedAt(LocalDate.of(2026, 8, 25)),
            TEST_CLOCK,
        )

        assertEquals(PreviousSevenDaysUsageResult.NoCompletedDays, result)
        assertEquals(null, aggregatedReader.requestedRange)
        assertEquals(emptyList<UsageTimeRange?>(), store.requestedRanges)
    }

    @Test
    fun `unavailable aggregate source does not become fake zero`() = runBlocking {
        val store = FixedTrackedUsageStore(28_000L, 35_000L)

        val result = PreviousSevenDaysUsageReader(
            store,
            FakeAggregatedAppUsageReader(AggregatedAppUsageReadResult.Unavailable),
        ).read(setupStartedAt(LocalDate.of(2026, 8, 10)), TEST_CLOCK)

        assertEquals(PreviousSevenDaysUsageResult.AggregatedUsageUnavailable, result)
        assertEquals(emptyList<UsageTimeRange?>(), store.requestedRanges)
    }

    @Test
    fun `successful empty aggregate source remains genuine zero`() = runBlocking {
        val result = PreviousSevenDaysUsageReader(
            FixedTrackedUsageStore(0L, 0L),
            FakeAggregatedAppUsageReader(AggregatedAppUsageReadResult.Available(emptyMap())),
        ).read(setupStartedAt(LocalDate.of(2026, 8, 10)), TEST_CLOCK)

        assertEquals(
            PreviousSevenDaysUsageResult.Available(
                PreviousSevenDaysAverages(0L, 0L),
                dayCount = 7,
            ),
            result,
        )
    }

    private class FakeAggregatedAppUsageReader(
        private val result: AggregatedAppUsageReadResult,
    ) : AggregatedAppUsageReader {
        var requestedRange: UsageTimeRange? = null

        override fun readAggregatedUsage(range: UsageTimeRange): AggregatedAppUsageReadResult {
            requestedRange = range
            return result
        }
    }

    private class FixedTrackedUsageStore(
        private val instagramReelsDurationMillis: Long,
        private val youTubeShortsDurationMillis: Long,
    ) : UsageSessionHistoryStore {
        val requestedRanges = mutableListOf<UsageTimeRange?>()

        override suspend fun getSessionsOverlapping(
            platform: String,
            usageType: String,
            rangeStartMillis: Long,
            rangeEndMillis: Long,
        ): List<UsageSessionEntity> {
            requestedRanges += UsageTimeRange(rangeStartMillis, rangeEndMillis)
            val durationMillis = when (platform) {
                "instagram" -> instagramReelsDurationMillis
                "youtube" -> youTubeShortsDurationMillis
                else -> error("Unexpected platform: $platform / $usageType")
            }
            return if (durationMillis == 0L) emptyList() else listOf(
                UsageSessionEntity(
                    platform = platform,
                    usageType = usageType,
                    startTimeMillis = rangeStartMillis,
                    endTimeMillis = rangeStartMillis + durationMillis,
                ),
            )
        }
    }

    private companion object {
        val TEST_ZONE: ZoneId = ZoneId.of("Asia/Jerusalem")
        val TEST_CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-08-25T08:15:00Z"),
            TEST_ZONE,
        )

        fun setupStartedAt(date: LocalDate): Long =
            date.atTime(12, 0).atZone(TEST_ZONE).toInstant().toEpochMilli()
    }
}
