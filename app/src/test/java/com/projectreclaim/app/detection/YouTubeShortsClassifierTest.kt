package com.projectreclaim.app.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeShortsClassifierTest {
    @Test
    fun `YouTube with any strong Shorts viewer marker is true`() {
        val strongMarkers = setOf(
            "com.google.android.youtube:id/reel_watch_fragment_root",
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_page_container",
            "com.google.android.youtube:id/reel_watch_player",
        )

        strongMarkers.forEach { marker ->
            assertTrue(
                marker,
                YouTubeShortsClassifier.isShortsActive(
                    YouTubeShortsSignals(
                        packageName = YouTubeShortsClassifier.YOUTUBE_PACKAGE,
                        matchedViewerResourceIds = setOf(marker),
                    ),
                ),
            )
        }
    }

    @Test
    fun `wrong package with strong Shorts markers is false`() {
        val signals = YouTubeShortsSignals(
            packageName = "com.example.video",
            matchedViewerResourceIds = YouTubeShortsClassifier.STRONG_VIEWER_RESOURCE_IDS,
        )

        assertFalse(YouTubeShortsClassifier.isShortsActive(signals))
    }

    @Test
    fun `reel time bar alone is false`() {
        val signals = YouTubeShortsSignals(
            packageName = YouTubeShortsClassifier.YOUTUBE_PACKAGE,
            matchedViewerResourceIds = setOf(
                "com.google.android.youtube:id/reel_time_bar",
            ),
        )

        assertFalse(YouTubeShortsClassifier.isShortsActive(signals))
    }

    @Test
    fun `normal player structural signals are false`() {
        val signals = YouTubeShortsSignals(
            packageName = YouTubeShortsClassifier.YOUTUBE_PACKAGE,
            matchedViewerResourceIds = setOf(
                "com.google.android.youtube:id/next_gen_watch_container_layout",
                "com.google.android.youtube:id/player_view",
                "com.google.android.youtube:id/watch_player",
                "com.google.android.youtube:id/watch_player_background",
                "com.google.android.youtube:id/reel_time_bar",
            ),
        )

        assertFalse(YouTubeShortsClassifier.isShortsActive(signals))
    }

    @Test
    fun `arbitrary reel resource ID is false`() {
        val signals = YouTubeShortsSignals(
            packageName = YouTubeShortsClassifier.YOUTUBE_PACKAGE,
            matchedViewerResourceIds = setOf(
                "com.google.android.youtube:id/reel_player_overlay_root",
            ),
        )

        assertFalse(YouTubeShortsClassifier.isShortsActive(signals))
    }
}
