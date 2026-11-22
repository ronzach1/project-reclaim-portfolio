package com.projectreclaim.app.data.repository

import com.projectreclaim.app.AppUsageReader
import com.projectreclaim.app.CalendarDayUsageTime
import com.projectreclaim.app.TIKTOK_PACKAGE_NAME
import java.time.Clock
import java.time.LocalDate

data class ShortFormHistoryDay(
    val date: LocalDate,
    val instagramReelsDurationMillis: Long,
    val tikTokDurationMillis: Long?,
    val youTubeShortsDurationMillis: Long,
) {
    val combinedShortFormDurationMillis: Long?
        get() = tikTokDurationMillis?.let { tikTokDuration ->
            instagramReelsDurationMillis + tikTokDuration + youTubeShortsDurationMillis
        }
}

/**
 * Reads eight local days of persisted Reels and Shorts sessions, plus today's TikTok foreground
 * usage. Completed-day TikTok and combined totals are unavailable until TikTok is persisted.
 */
class ShortFormHistoryReader(
    usageSessionHistoryStore: UsageSessionHistoryStore,
    private val appUsageReader: AppUsageReader,
) {
    private val trackedUsageReader = TrackedUsageReader(usageSessionHistoryStore)

    suspend fun read(clock: Clock = Clock.systemDefaultZone()): List<ShortFormHistoryDay> {
        val days = CalendarDayUsageTime.history(clock)
        val todayTikTokDurationMillis = appUsageReader
            .readUsage(days.first().range, clock.zone)
            .firstOrNull { it.packageName == TIKTOK_PACKAGE_NAME }
            ?.durationMillis
            ?: 0L

        return days.mapIndexed { index, day ->
            ShortFormHistoryDay(
                date = day.date,
                instagramReelsDurationMillis = trackedUsageReader.readDurationMillis(
                    platform = INSTAGRAM_PLATFORM,
                    usageType = REELS_USAGE_TYPE,
                    range = day.range,
                ),
                tikTokDurationMillis = if (index == 0) todayTikTokDurationMillis else null,
                youTubeShortsDurationMillis = trackedUsageReader.readDurationMillis(
                    platform = YOUTUBE_PLATFORM,
                    usageType = SHORTS_USAGE_TYPE,
                    range = day.range,
                ),
            )
        }
    }

    private companion object {
        const val INSTAGRAM_PLATFORM = "instagram"
        const val REELS_USAGE_TYPE = "reels"
        const val YOUTUBE_PLATFORM = "youtube"
        const val SHORTS_USAGE_TYPE = "shorts"
    }
}
