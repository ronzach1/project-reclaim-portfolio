package com.projectreclaim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackedUsageDurationFormatterTest {
    @Test
    fun `formats zero as zero seconds`() {
        assertEquals("0s", TrackedUsageDurationFormatter.format(0))
    }

    @Test
    fun `floors duration under one second`() {
        assertEquals("0s", TrackedUsageDurationFormatter.format(999))
    }

    @Test
    fun `formats seconds only`() {
        assertEquals("12s", TrackedUsageDurationFormatter.format(12_000))
    }

    @Test
    fun `formats minutes and seconds`() {
        assertEquals("1m 12s", TrackedUsageDurationFormatter.format(72_000))
    }

    @Test
    fun `formats exact minute`() {
        assertEquals("1m", TrackedUsageDurationFormatter.format(60_000))
    }

    @Test
    fun `formats hours minutes and seconds`() {
        assertEquals("1h 1m 12s", TrackedUsageDurationFormatter.format(3_672_000))
    }

    @Test
    fun `formats exact hour`() {
        assertEquals("1h", TrackedUsageDurationFormatter.format(3_600_000))
    }

    @Test
    fun `formats negative duration as zero seconds`() {
        assertEquals("0s", TrackedUsageDurationFormatter.format(-1))
    }
}
