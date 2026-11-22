package com.projectreclaim.app.data.repository

import com.projectreclaim.app.UsageTimeRange
import com.projectreclaim.app.data.local.UsageSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageSessionDurationCalculatorTest {
    private val range = UsageTimeRange(startMillis = 100, endMillis = 200)

    @Test
    fun `returns zero for no sessions`() {
        assertEquals(0L, UsageSessionDurationCalculator.calculate(emptyList(), range))
    }

    @Test
    fun `counts closed session entirely inside range`() {
        assertEquals(40L, total(session(start = 120, end = 160)))
    }

    @Test
    fun `clips session crossing range start`() {
        assertEquals(30L, total(session(start = 50, end = 130)))
    }

    @Test
    fun `clips session crossing range end`() {
        assertEquals(30L, total(session(start = 170, end = 250)))
    }

    @Test
    fun `counts exactly range duration for session spanning entire range`() {
        assertEquals(100L, total(session(start = 50, end = 250)))
    }

    @Test
    fun `open session counts through range end`() {
        assertEquals(40L, total(session(start = 160, end = null)))
    }

    @Test
    fun `session ending exactly at range start contributes zero`() {
        assertEquals(0L, total(session(start = 50, end = 100)))
    }

    @Test
    fun `session starting exactly at range end contributes zero`() {
        assertEquals(0L, total(session(start = 200, end = 250)))
    }

    @Test
    fun `malformed end before start never contributes negative duration`() {
        assertEquals(0L, total(session(start = 180, end = 120)))
    }

    @Test
    fun `sums multiple valid sessions`() {
        assertEquals(60L, total(session(start = 110, end = 130), session(start = 150, end = 190)))
    }

    private fun total(vararg sessions: UsageSessionEntity): Long =
        UsageSessionDurationCalculator.calculate(sessions.toList(), range)

    private fun session(start: Long, end: Long?) = UsageSessionEntity(
        platform = "instagram",
        usageType = "reels",
        startTimeMillis = start,
        endTimeMillis = end,
    )
}
