package com.projectreclaim.app

import com.projectreclaim.app.data.repository.ShortFormHistoryDay
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompletedHistoryDaysTest {
    @Test
    fun `chart excludes today and shows seven completed days oldest first`() {
        val today = LocalDate.of(2026, 11, 4)
        val history = (0L..7L).map { daysAgo ->
            ShortFormHistoryDay(today.minusDays(daysAgo), daysAgo * 1_000L,
                if (daysAgo == 0L) 99_000L else null, daysAgo * 2_000L)
        }
        val days = completedHistoryDays(history)
        assertEquals((7L downTo 1L).map(today::minusDays), days.map { it.date })
        assertEquals(listOf(7_000L, 6_000L, 5_000L, 4_000L, 3_000L, 2_000L, 1_000L),
            days.map { it.instagramReelsDurationMillis })
        days.forEach {
            assertNull(it.tikTokDurationMillis)
            assertNull(it.combinedShortFormDurationMillis)
        }
    }

    @Test
    fun `empty and today-only history do not fabricate completed days`() {
        assertEquals(emptyList<ShortFormHistoryDay>(), completedHistoryDays(emptyList()))
        assertEquals(emptyList<ShortFormHistoryDay>(), completedHistoryDays(listOf(
            ShortFormHistoryDay(LocalDate.of(2026, 11, 4), 0L, 0L, 0L),
        )))
    }

    @Test
    fun `saved zero remains a numeric zero with unavailable historical TikTok`() {
        val days = completedHistoryDays(listOf(
            ShortFormHistoryDay(LocalDate.of(2026, 11, 4), 0L, 0L, 0L),
            ShortFormHistoryDay(LocalDate.of(2026, 11, 3), 0L, null, 0L),
        ))
        assertEquals(0L, days.single().instagramReelsDurationMillis)
        assertEquals(0L, days.single().youTubeShortsDurationMillis)
        assertNull(days.single().combinedShortFormDurationMillis)
    }
}
