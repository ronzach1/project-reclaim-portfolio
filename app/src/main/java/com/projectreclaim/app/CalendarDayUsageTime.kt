package com.projectreclaim.app

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class UsageTimeRange(val startMillis: Long, val endMillis: Long)

data class CalendarDayUsageRange(
    val date: LocalDate,
    val range: UsageTimeRange,
)

/** Produces exact local-calendar-day ranges for short-form history. */
object CalendarDayUsageTime {
    fun history(clock: Clock = Clock.systemDefaultZone()): List<CalendarDayUsageRange> {
        val zone = clock.zone
        val now = clock.instant()
        val today = now.atZone(zone).toLocalDate()

        return (0 until HISTORY_DAY_COUNT).map { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            CalendarDayUsageRange(
                date = date,
                range = UsageTimeRange(
                    startMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    endMillis = if (daysAgo == 0) {
                        now.toEpochMilli()
                    } else {
                        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    },
                ),
            )
        }
    }

    private const val HISTORY_DAY_COUNT = 8
}

object TodayUsageTime {
    fun range(clock: Clock): UsageTimeRange = CalendarDayUsageTime.history(clock).first().range

    fun lookbackStart(clock: Clock): Long = lookbackStart(range(clock), clock.zone)

    fun lookbackStart(range: UsageTimeRange, zone: ZoneId): Long =
        Instant.ofEpochMilli(range.startMillis)
            .atZone(zone)
            .toLocalDate()
            .minusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
}

internal data class PreviousSevenDaysCoverage(
    val range: UsageTimeRange?,
    val dayCount: Int,
)

/** Finds up to seven completed local calendar days with full measurement coverage. */
internal object PreviousSevenDaysUsageTime {
    const val DAY_COUNT = 7

    fun coverage(
        fullMeasurementSetupStartedAt: Long,
        clock: Clock = Clock.systemDefaultZone(),
    ): PreviousSevenDaysCoverage {
        val zone = clock.zone
        val today = clock.instant().atZone(zone).toLocalDate()
        val firstFullyCoveredDay = Instant.ofEpochMilli(fullMeasurementSetupStartedAt)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
        val startDate = maxOf(firstFullyCoveredDay, today.minusDays(DAY_COUNT.toLong()))
        val dayCount = ChronoUnit.DAYS.between(startDate, today)
            .coerceIn(0, DAY_COUNT.toLong())
            .toInt()

        if (dayCount == 0) {
            return PreviousSevenDaysCoverage(range = null, dayCount = 0)
        }

        return PreviousSevenDaysCoverage(
            range = UsageTimeRange(
                startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                endMillis = today.atStartOfDay(zone).toInstant().toEpochMilli(),
            ),
            dayCount = dayCount,
        )
    }
}
