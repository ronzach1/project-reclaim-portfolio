package com.projectreclaim.app

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CalendarDayUsageTimeTest {
    @Test
    fun `history contains today through seven days ago in descending date order`() {
        val clock = Clock.fixed(Instant.parse("2026-08-25T08:15:00Z"), ZoneId.of("Asia/Jerusalem"))

        val days = CalendarDayUsageTime.history(clock)

        assertEquals(8, days.size)
        assertEquals(
            (0L..7L).map { LocalDate.of(2026, 8, 25).minusDays(it) },
            days.map { it.date },
        )
    }

    @Test
    fun `today ends at clock now and previous day spans local midnight boundaries`() {
        val zone = ZoneId.of("Asia/Jerusalem")
        val now = Instant.parse("2026-08-25T08:15:00Z")
        val days = CalendarDayUsageTime.history(Clock.fixed(now, zone))

        assertEquals(now.toEpochMilli(), days[0].range.endMillis)
        assertEquals(
            LocalDate.of(2026, 8, 24).atStartOfDay(zone).toInstant().toEpochMilli(),
            days[1].range.startMillis,
        )
        assertEquals(
            LocalDate.of(2026, 8, 25).atStartOfDay(zone).toInstant().toEpochMilli(),
            days[1].range.endMillis,
        )
    }

    @Test
    fun `DST transition day uses actual consecutive local midnight boundaries`() {
        val zone = ZoneId.of("America/New_York")
        val days = CalendarDayUsageTime.history(
            Clock.fixed(Instant.parse("2026-03-09T16:00:00Z"), zone),
        )
        val springForwardDay = days.single { it.date == LocalDate.of(2026, 3, 8) }.range

        assertEquals(Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(), springForwardDay.startMillis)
        assertEquals(Instant.parse("2026-03-09T04:00:00Z").toEpochMilli(), springForwardDay.endMillis)
        assertEquals(Duration.ofHours(23).toMillis(), springForwardDay.endMillis - springForwardDay.startMillis)
        assertNotEquals(Duration.ofHours(24).toMillis(), springForwardDay.endMillis - springForwardDay.startMillis)
    }

    @Test
    fun `setup on a partial first day has no completed coverage`() {
        val zone = ZoneId.of("America/New_York")
        val clock = Clock.fixed(Instant.parse("2026-03-10T16:00:00Z"), zone)
        val setupStartedAt = Instant.parse("2026-03-10T05:30:00Z").toEpochMilli()

        val coverage = PreviousSevenDaysUsageTime.coverage(setupStartedAt, clock)

        assertEquals(0, coverage.dayCount)
        assertEquals(null, coverage.range)
    }

    @Test
    fun `coverage uses completed local days after setup and stays DST safe`() {
        val zone = ZoneId.of("America/New_York")
        val clock = Clock.fixed(Instant.parse("2026-03-10T16:00:00Z"), zone)
        val setupStartedAt = Instant.parse("2026-03-07T17:00:00Z").toEpochMilli()

        val coverage = PreviousSevenDaysUsageTime.coverage(setupStartedAt, clock)

        assertEquals(2, coverage.dayCount)
        assertEquals(
            UsageTimeRange(
                LocalDate.of(2026, 3, 8).atStartOfDay(zone).toInstant().toEpochMilli(),
                LocalDate.of(2026, 3, 10).atStartOfDay(zone).toInstant().toEpochMilli(),
            ),
            coverage.range,
        )
        assertEquals(Duration.ofHours(47).toMillis(), coverage.range!!.endMillis - coverage.range.startMillis)
    }

    @Test
    fun `coverage caps at the most recent seven completed days`() {
        val zone = ZoneId.of("America/New_York")
        val clock = Clock.fixed(Instant.parse("2026-03-10T16:00:00Z"), zone)
        val setupStartedAt = Instant.parse("2026-02-20T17:00:00Z").toEpochMilli()

        val coverage = PreviousSevenDaysUsageTime.coverage(setupStartedAt, clock)

        assertEquals(7, coverage.dayCount)
        assertEquals(
            UsageTimeRange(
                LocalDate.of(2026, 3, 3).atStartOfDay(zone).toInstant().toEpochMilli(),
                LocalDate.of(2026, 3, 10).atStartOfDay(zone).toInstant().toEpochMilli(),
            ),
            coverage.range,
        )
    }
}
