package com.projectreclaim.app.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class ReelsEvaluationPolicyTest {
    @Test
    fun `non Instagram window state clears immediately`() {
        assertEquals(
            ReelsEvaluationAction.CLEAR_IMMEDIATELY,
            ReelsEvaluationPolicy.actionFor(
                ReelsAccessibilityEventType.WINDOW_STATE_CHANGED,
                "com.android.launcher3",
            ),
        )
    }

    @Test
    fun `Instagram window state reevaluates fast`() {
        assertEquals(
            ReelsEvaluationAction.FAST_REEVALUATE,
            ReelsEvaluationPolicy.actionFor(
                ReelsAccessibilityEventType.WINDOW_STATE_CHANGED,
                InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            ),
        )
    }

    @Test
    fun `system windows change reevaluates fast`() {
        assertEquals(
            ReelsEvaluationAction.FAST_REEVALUATE,
            ReelsEvaluationPolicy.actionFor(
                ReelsAccessibilityEventType.WINDOWS_CHANGED,
                "com.android.systemui",
            ),
        )
    }

    @Test
    fun `Instagram content change uses debounce`() {
        assertEquals(
            ReelsEvaluationAction.DEBOUNCED_REEVALUATE,
            ReelsEvaluationPolicy.actionFor(
                ReelsAccessibilityEventType.WINDOW_CONTENT_CHANGED,
                InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            ),
        )
    }

    @Test
    fun `view scrolled is ignored`() {
        assertEquals(
            ReelsEvaluationAction.IGNORE,
            ReelsEvaluationPolicy.actionFor(
                ReelsAccessibilityEventType.VIEW_SCROLLED,
                InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            ),
        )
    }

    @Test
    fun `non Instagram content change is ignored`() {
        assertEquals(
            ReelsEvaluationAction.IGNORE,
            ReelsEvaluationPolicy.actionFor(
                ReelsAccessibilityEventType.WINDOW_CONTENT_CHANGED,
                "com.android.systemui",
            ),
        )
    }
}
