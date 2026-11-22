package com.projectreclaim.app

import com.projectreclaim.app.data.repository.ShortFormHistoryDay
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortFormHistoryPresentationTest {
    @Test
    fun `available history preserves all eight rows in supplied order`() {
        val history = (0L..7L).map { daysAgo ->
            day(
                date = LocalDate.of(2026, 8, 25).minusDays(daysAgo),
                tikTokDurationMillis = if (daysAgo == 0L) 30_000L else null,
                instagramReelsDurationMillis = (daysAgo + 1L) * 1_000L,
            )
        }

        val available = ShortFormHistoryState.Available(history)
        val rows = ShortFormHistoryPresentation.rows(available.history)

        assertEquals(history, available.history)
        assertEquals(8, rows.size)
        assertEquals("Today", rows.first().dateLabel)
        assertEquals(
            (1L..8L).map { "${it}s" },
            rows.map(ShortFormHistoryDisplayRow::instagramReelsDurationText),
        )
    }

    @Test
    fun `today keeps numeric combined duration`() {
        val row = ShortFormHistoryPresentation.rows(
            listOf(day(LocalDate.of(2026, 8, 25), tikTokDurationMillis = 30_000L)),
        ).single()

        assertEquals("1m 10s", row.combinedDurationText)
        assertEquals("30s", row.tikTokDurationText)
    }

    @Test
    fun `historical unavailable values are explicitly not available`() {
        val row = ShortFormHistoryPresentation.rows(
            listOf(
                day(LocalDate.of(2026, 8, 25), tikTokDurationMillis = 30_000L),
                day(LocalDate.of(2026, 8, 24), tikTokDurationMillis = null),
            ),
        ).last()

        assertEquals("Not available", row.combinedDurationText)
        assertEquals("Not available", row.tikTokDurationText)
    }

    @Test
    fun `history requires Usage Access when TikTok cannot be read`() {
        assertEquals(
            ShortFormHistoryState.PermissionRequired,
            ShortFormHistoryStateCalculator.initialState(hasUsageAccess = false),
        )
    }

    private fun day(
        date: LocalDate,
        tikTokDurationMillis: Long?,
        instagramReelsDurationMillis: Long = 20_000L,
    ) = ShortFormHistoryDay(
        date = date,
        instagramReelsDurationMillis = instagramReelsDurationMillis,
        tikTokDurationMillis = tikTokDurationMillis,
        youTubeShortsDurationMillis = 20_000L,
    )
}
