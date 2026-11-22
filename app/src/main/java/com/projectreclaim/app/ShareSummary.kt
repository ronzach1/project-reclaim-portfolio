package com.projectreclaim.app

import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class SharePeriod(val label: String) { TODAY("Today"), WEEK("Week") }

internal data class ShareSummary(
    val period: SharePeriod,
    val dateLabel: String,
    val scope: String,
    val totalMillis: Long?,
    val status: String,
    val instagramMillis: Long? = null,
    val youTubeMillis: Long? = null,
    val tikTokMillis: Long? = null,
    val trackingPaused: Boolean = false,
) {
    val totalLabel: String
        get() = totalMillis?.let(TrackedUsageDurationFormatter::format) ?: "Unavailable"

    val breakdown: List<Pair<String, String>>
        get() = listOf(
            "Instagram Reels" to instagramMillis,
            "YouTube Shorts" to youTubeMillis,
            "TikTok" to tikTokMillis,
        ).map { (name, duration) ->
            name to (duration?.let(TrackedUsageDurationFormatter::format) ?: "Unavailable")
        }

    val measurementNote: String
        get() = if (period == SharePeriod.WEEK) {
            "TikTok counts all foreground app time. Android may extend aggregate time beyond the shown dates. Recorded time may omit tracking gaps."
        } else {
            val note = "TikTok counts all foreground app time. Recorded time may omit tracking gaps."
            if (trackingPaused && totalMillis == null) {
                "Reels and Shorts tracking is paused. $note"
            } else {
                note
            }
        }

    companion object {
        private val dateFormat = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH)

        fun today(
            date: LocalDate,
            instagram: TrackedUsageState,
            youTube: TrackedUsageState,
            tikTok: TikTokTodayState,
            accessibilityEnabled: Boolean?,
        ): ShareSummary {
            val state = ShortFormTodayPresentationCalculator.calculate(
                ShortFormTodayStateCalculator.calculate(instagram, tikTok, youTube),
                accessibilityEnabled,
            )
            val total = when (state) {
                is ShortFormTodayPresentation.Complete -> state.durationMillis
                is ShortFormTodayPresentation.Partial -> state.durationMillis
                else -> null
            }
            val status = when (state) {
                is ShortFormTodayPresentation.Complete -> "Recorded short-form time"
                is ShortFormTodayPresentation.Partial -> "Partial · Accessibility tracking paused"
                ShortFormTodayPresentation.PermissionRequired -> "Usage Access needed for TikTok"
                ShortFormTodayPresentation.Loading,
                ShortFormTodayPresentation.Unavailable -> "Complete total unavailable"
            }
            return ShareSummary(
                SharePeriod.TODAY, date.format(dateFormat), "Today", total, status,
                (instagram as? TrackedUsageState.Available)?.durationMillis,
                (youTube as? TrackedUsageState.Available)?.durationMillis,
                (tikTok as? TikTokTodayState.Available)?.durationMillis,
                trackingPaused = accessibilityEnabled == false,
            )
        }

        suspend fun week(
            setupStartedAt: Long?,
            hasUsageAccess: Boolean,
            clock: Clock,
            readTotals: suspend (UsageTimeRange) -> CompletedDaysUsageTotals?,
        ): ShareSummary {
            val coverage = setupStartedAt?.let { PreviousSevenDaysUsageTime.coverage(it, clock) }
            val range = coverage?.range ?: return ShareSummary(
                SharePeriod.WEEK, "", "0 eligible completed days", null,
                if (setupStartedAt == null) "Usage Access and Accessibility setup needed"
                else "No completed days since setup",
            )
            val last = LocalDate.now(clock).minusDays(1)
            val first = last.minusDays(coverage.dayCount - 1L)
            val dates = if (first == last) last.format(dateFormat)
                else "${first.format(dateFormat)} – ${last.format(dateFormat)}"
            val dayLabel = if (coverage.dayCount == 1) "day" else "days"
            val totals = if (hasUsageAccess) readTotals(range) else null
            return ShareSummary(
                SharePeriod.WEEK, dates, "${coverage.dayCount} eligible completed $dayLabel",
                totals?.shortFormTotalMillis,
                when {
                    !hasUsageAccess -> "Usage Access needed for TikTok"
                    totals == null -> "Complete total unavailable"
                    else -> "Recorded short-form time"
                },
                totals?.instagramReelsMillis, totals?.youTubeShortsMillis, totals?.tikTokMillis,
            )
        }
    }
}
