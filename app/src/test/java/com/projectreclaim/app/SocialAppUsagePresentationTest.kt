package com.projectreclaim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SocialAppUsagePresentationTest {
    @Test
    fun `total social app usage sums the already loaded app durations`() {
        assertEquals(
            195_000L,
            socialAppUsageTotalMillis(
                listOf(
                    AppUsage("instagram", "Instagram", 60_000L),
                    AppUsage("tiktok", "TikTok", 90_000L),
                    AppUsage("facebook", "Facebook", 15_000L),
                    AppUsage(X_PACKAGE_NAME, "X", 30_000L),
                ),
            ),
        )
    }
}
