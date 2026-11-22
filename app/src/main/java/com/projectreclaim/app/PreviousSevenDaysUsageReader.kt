package com.projectreclaim.app

import com.projectreclaim.app.data.repository.TrackedUsageReader
import com.projectreclaim.app.data.repository.UsageSessionHistoryStore
import java.time.Clock

sealed interface AggregatedAppUsageReadResult {
    data object Unavailable : AggregatedAppUsageReadResult
    data class Available(val durationByPackage: Map<String, Long>) : AggregatedAppUsageReadResult
}

interface AggregatedAppUsageReader {
    fun readAggregatedUsage(range: UsageTimeRange): AggregatedAppUsageReadResult
}

internal data class PreviousSevenDaysAverages(
    val shortFormDailyAverageMillis: Long,
    val socialAppsDailyAverageMillis: Long,
)

internal sealed interface PreviousSevenDaysUsageResult {
    data object NoCompletedDays : PreviousSevenDaysUsageResult
    data object AggregatedUsageUnavailable : PreviousSevenDaysUsageResult
    data class Available(
        val averages: PreviousSevenDaysAverages,
        val dayCount: Int,
    ) : PreviousSevenDaysUsageResult
}

internal object PreviousSevenDaysAverageCalculator {
    fun calculate(
        instagramReelsTotalMillis: Long,
        youTubeShortsTotalMillis: Long,
        aggregatedUsageByPackage: Map<String, Long>,
        dayCount: Int,
    ): PreviousSevenDaysAverages {
        require(dayCount in 1..PreviousSevenDaysUsageTime.DAY_COUNT)
        val tikTokTotalMillis = aggregatedUsageByPackage[TIKTOK_PACKAGE_NAME] ?: 0L
        val socialAppsTotalMillis = SUPPORTED_SOCIAL_PACKAGE_NAMES.sumOf { packageName ->
            aggregatedUsageByPackage[packageName] ?: 0L
        }
        val shortFormTotalMillis =
            instagramReelsTotalMillis + youTubeShortsTotalMillis + tikTokTotalMillis
        return PreviousSevenDaysAverages(
            shortFormDailyAverageMillis =
                shortFormTotalMillis / dayCount,
            socialAppsDailyAverageMillis =
                socialAppsTotalMillis / dayCount,
        )
    }
}

internal data class CompletedDaysUsageTotals(
    val instagramReelsMillis: Long,
    val youTubeShortsMillis: Long,
    val appUsageMillis: Map<String, Long>,
) {
    val tikTokMillis: Long get() = appUsageMillis[TIKTOK_PACKAGE_NAME] ?: 0L
    val shortFormTotalMillis: Long get() = instagramReelsMillis + youTubeShortsMillis + tikTokMillis
}

internal class PreviousSevenDaysUsageReader(
    usageSessionHistoryStore: UsageSessionHistoryStore,
    private val aggregatedAppUsageReader: AggregatedAppUsageReader,
) {
    private val trackedUsageReader = TrackedUsageReader(usageSessionHistoryStore)

    suspend fun read(
        fullMeasurementSetupStartedAt: Long,
        clock: Clock = Clock.systemDefaultZone(),
    ): PreviousSevenDaysUsageResult {
        val coverage = PreviousSevenDaysUsageTime.coverage(fullMeasurementSetupStartedAt, clock)
        val range = coverage.range ?: return PreviousSevenDaysUsageResult.NoCompletedDays
        val totals = readTotals(range) ?: return PreviousSevenDaysUsageResult.AggregatedUsageUnavailable
        return PreviousSevenDaysUsageResult.Available(
            PreviousSevenDaysAverageCalculator.calculate(
                instagramReelsTotalMillis = totals.instagramReelsMillis,
                youTubeShortsTotalMillis = totals.youTubeShortsMillis,
                aggregatedUsageByPackage = totals.appUsageMillis,
                dayCount = coverage.dayCount,
            ),
            dayCount = coverage.dayCount,
        )
    }

    suspend fun readTotals(range: UsageTimeRange): CompletedDaysUsageTotals? {
        val aggregatedUsage = aggregatedAppUsageReader.readAggregatedUsage(range)
        if (aggregatedUsage is AggregatedAppUsageReadResult.Unavailable) {
            return null
        }
        aggregatedUsage as AggregatedAppUsageReadResult.Available

        val instagramReelsTotalMillis = trackedUsageReader.readDurationMillis(
            platform = INSTAGRAM_PLATFORM,
            usageType = REELS_USAGE_TYPE,
            range = range,
        )
        val youTubeShortsTotalMillis = trackedUsageReader.readDurationMillis(
            platform = YOUTUBE_PLATFORM,
            usageType = SHORTS_USAGE_TYPE,
            range = range,
        )

        return CompletedDaysUsageTotals(
            instagramReelsTotalMillis,
            youTubeShortsTotalMillis,
            aggregatedUsage.durationByPackage,
        )
    }

    private companion object {
        const val INSTAGRAM_PLATFORM = "instagram"
        const val REELS_USAGE_TYPE = "reels"
        const val YOUTUBE_PLATFORM = "youtube"
        const val SHORTS_USAGE_TYPE = "shorts"
    }
}
