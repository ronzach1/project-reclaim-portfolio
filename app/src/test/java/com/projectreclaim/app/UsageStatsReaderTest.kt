package com.projectreclaim.app

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageStatsReaderTest {
    @Test
    fun `formats minutes below an hour`() {
        assertEquals("17m", UsageDurationFormatter.format(17 * 60_000L))
    }

    @Test
    fun `formats hours and minutes`() {
        assertEquals("1h 23m", UsageDurationFormatter.format(83 * 60_000L))
    }

    @Test
    fun `uses the clocks local start of day`() {
        val zone = ZoneId.of("Asia/Jerusalem")
        val clock = Clock.fixed(Instant.parse("2026-08-25T08:15:00Z"), zone)

        assertEquals(Instant.parse("2026-08-24T21:00:00Z").toEpochMilli(), TodayUsageTime.range(clock).startMillis)
        assertEquals(Instant.parse("2026-08-25T08:15:00Z").toEpochMilli(), TodayUsageTime.range(clock).endMillis)
    }

    @Test
    fun `lookback starts at previous local midnight across DST`() {
        val zone = ZoneId.of("America/New_York")
        val clock = Clock.fixed(Instant.parse("2026-03-09T16:00:00Z"), zone)

        assertEquals(
            Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(),
            TodayUsageTime.lookbackStart(clock),
        )
    }

    @Test
    fun `historical range lookback starts at its previous local midnight`() {
        val zone = ZoneId.of("Asia/Jerusalem")
        val clock = Clock.fixed(Instant.parse("2026-08-25T08:15:00Z"), zone)
        val historicalRange = CalendarDayUsageTime.history(clock)[3].range

        assertEquals(
            LocalDate.of(2026, 8, 21).atStartOfDay(zone).toInstant().toEpochMilli(),
            TodayUsageTime.lookbackStart(historicalRange, zone),
        )
    }

    @Test
    fun `X is included in supported social foreground apps`() {
        assertEquals(
            listOf("Instagram", "TikTok", "Facebook", "X"),
            SUPPORTED_SOCIAL_APPS.map(SupportedSocialApp::name),
        )
        assertEquals(
            setOf(
                INSTAGRAM_PACKAGE_NAME,
                TIKTOK_PACKAGE_NAME,
                FACEBOOK_PACKAGE_NAME,
                X_PACKAGE_NAME,
            ),
            SUPPORTED_SOCIAL_PACKAGE_NAMES,
        )
    }
}
