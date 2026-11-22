package com.projectreclaim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortFormTodayStateCalculatorTest {
    @Test
    fun `sums all available source durations as raw milliseconds`() {
        assertEquals(
            ShortFormTodayState.Available(99_000L),
            ShortFormTodayStateCalculator.calculate(
                instagramReels = TrackedUsageState.Available(12_000L),
                tikTok = TikTokTodayState.Available(72_000L),
                youTubeShorts = TrackedUsageState.Available(15_000L),
            ),
        )
    }

    @Test
    fun `requires Usage Access when TikTok cannot be read`() {
        assertEquals(
            ShortFormTodayState.PermissionRequired,
            ShortFormTodayStateCalculator.calculate(
                instagramReels = TrackedUsageState.Available(12_000L),
                tikTok = TikTokTodayState.PermissionRequired,
                youTubeShorts = TrackedUsageState.Available(15_000L),
            ),
        )
    }

    @Test
    fun `Usage Access requirement takes priority over tracked read failures`() {
        assertEquals(
            ShortFormTodayState.PermissionRequired,
            ShortFormTodayStateCalculator.calculate(
                instagramReels = TrackedUsageState.Unavailable,
                tikTok = TikTokTodayState.PermissionRequired,
                youTubeShorts = TrackedUsageState.Unavailable,
            ),
        )
    }

    @Test
    fun `is unavailable when Instagram tracked usage cannot be read`() {
        assertEquals(
            ShortFormTodayState.Unavailable,
            ShortFormTodayStateCalculator.calculate(
                instagramReels = TrackedUsageState.Unavailable,
                tikTok = TikTokTodayState.Available(72_000L),
                youTubeShorts = TrackedUsageState.Available(15_000L),
            ),
        )
    }

    @Test
    fun `is unavailable when YouTube Shorts tracked usage cannot be read`() {
        assertEquals(
            ShortFormTodayState.Unavailable,
            ShortFormTodayStateCalculator.calculate(
                instagramReels = TrackedUsageState.Available(12_000L),
                tikTok = TikTokTodayState.Available(72_000L),
                youTubeShorts = TrackedUsageState.Unavailable,
            ),
        )
    }

    @Test
    fun `is loading when Instagram Reels is loading`() {
        assertEquals(
            ShortFormTodayState.Loading,
            ShortFormTodayStateCalculator.calculate(
                instagramReels = TrackedUsageState.Loading,
                tikTok = TikTokTodayState.Available(72_000L),
                youTubeShorts = TrackedUsageState.Available(15_000L),
            ),
        )
    }

    @Test
    fun `is loading when TikTok is loading`() {
        assertEquals(
            ShortFormTodayState.Loading,
            ShortFormTodayStateCalculator.calculate(
                instagramReels = TrackedUsageState.Available(12_000L),
                tikTok = TikTokTodayState.Loading,
                youTubeShorts = TrackedUsageState.Available(15_000L),
            ),
        )
    }

    @Test
    fun `is loading when YouTube Shorts is loading`() {
        assertEquals(
            ShortFormTodayState.Loading,
            ShortFormTodayStateCalculator.calculate(
                instagramReels = TrackedUsageState.Available(12_000L),
                tikTok = TikTokTodayState.Available(72_000L),
                youTubeShorts = TrackedUsageState.Loading,
            ),
        )
    }
}
