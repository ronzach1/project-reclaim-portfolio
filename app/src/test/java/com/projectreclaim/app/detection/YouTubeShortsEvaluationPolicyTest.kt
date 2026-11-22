package com.projectreclaim.app.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeShortsEvaluationPolicyTest {
    @Test
    fun `YouTube window state reevaluates fast`() {
        assertEquals(
            YouTubeShortsEvaluationAction.FAST_REEVALUATE,
            YouTubeShortsEvaluationPolicy.actionFor(
                ReelsAccessibilityEventType.WINDOW_STATE_CHANGED,
                YouTubeShortsClassifier.YOUTUBE_PACKAGE,
            ),
        )
    }

    @Test
    fun `YouTube content change uses debounce`() {
        assertEquals(
            YouTubeShortsEvaluationAction.DEBOUNCED_REEVALUATE,
            YouTubeShortsEvaluationPolicy.actionFor(
                ReelsAccessibilityEventType.WINDOW_CONTENT_CHANGED,
                YouTubeShortsClassifier.YOUTUBE_PACKAGE,
            ),
        )
    }

    @Test
    fun `non YouTube window state clears immediately`() {
        assertEquals(
            YouTubeShortsEvaluationAction.CLEAR_IMMEDIATELY,
            YouTubeShortsEvaluationPolicy.actionFor(
                ReelsAccessibilityEventType.WINDOW_STATE_CHANGED,
                InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            ),
        )
    }

    @Test
    fun `non YouTube content change is ignored`() {
        assertEquals(
            YouTubeShortsEvaluationAction.IGNORE,
            YouTubeShortsEvaluationPolicy.actionFor(
                ReelsAccessibilityEventType.WINDOW_CONTENT_CHANGED,
                InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            ),
        )
    }
}
