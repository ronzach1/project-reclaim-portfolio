package com.projectreclaim.app.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramReelsClassifierTest {
    @Test
    fun `non Instagram state is false even with viewer signals`() {
        val signals = InstagramReelsSignals(
            packageName = "com.example.video",
            hasKnownViewerResourceId = true,
            hasScrollablePagingContainer = true,
            actionControls = ReelsActionControl.entries.toSet(),
        )

        assertFalse(InstagramReelsClassifier.isReelsActive(signals))
    }

    @Test
    fun `Instagram without Reels indicators is false`() {
        val signals = InstagramReelsSignals(
            packageName = InstagramReelsClassifier.INSTAGRAM_PACKAGE,
        )

        assertFalse(InstagramReelsClassifier.isReelsActive(signals))
    }

    @Test
    fun `known Reels viewer plus paging structure is true`() {
        val signals = InstagramReelsSignals(
            packageName = InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            hasKnownViewerResourceId = true,
            hasScrollablePagingContainer = true,
        )

        assertTrue(InstagramReelsClassifier.isReelsActive(signals))
    }

    @Test
    fun `known Reels viewer plus two distinct actions is true`() {
        val signals = InstagramReelsSignals(
            packageName = InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            hasKnownViewerResourceId = true,
            actionControls = setOf(
                ReelsActionControl.LIKE,
                ReelsActionControl.COMMENT,
            ),
        )

        assertTrue(InstagramReelsClassifier.isReelsActive(signals))
    }

    @Test
    fun `known Reels viewer plus one action is false`() {
        val signals = InstagramReelsSignals(
            packageName = InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            hasKnownViewerResourceId = true,
            actionControls = setOf(ReelsActionControl.LIKE),
        )

        assertFalse(InstagramReelsClassifier.isReelsActive(signals))
    }

    @Test
    fun `selected Reels destination plus paging and all actions is true`() {
        val signals = InstagramReelsSignals(
            packageName = InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            hasSelectedReelsDestination = true,
            hasScrollablePagingContainer = true,
            actionControls = ReelsActionControl.entries.toSet(),
        )

        assertTrue(InstagramReelsClassifier.isReelsActive(signals))
    }

    @Test
    fun `selected Reels destination plus paging and two actions is false`() {
        val signals = InstagramReelsSignals(
            packageName = InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            hasSelectedReelsDestination = true,
            hasScrollablePagingContainer = true,
            actionControls = setOf(
                ReelsActionControl.LIKE,
                ReelsActionControl.SHARE,
            ),
        )

        assertFalse(InstagramReelsClassifier.isReelsActive(signals))
    }

    @Test
    fun `selected Reels destination plus all actions without paging is false`() {
        val signals = InstagramReelsSignals(
            packageName = InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            hasSelectedReelsDestination = true,
            actionControls = ReelsActionControl.entries.toSet(),
        )

        assertFalse(InstagramReelsClassifier.isReelsActive(signals))
    }

    @Test
    fun `ambiguous partial indicators are false`() {
        val knownIdOnly = InstagramReelsSignals(
            packageName = InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            hasKnownViewerResourceId = true,
        )
        val selectedTabAndActionsWithoutPaging = InstagramReelsSignals(
            packageName = InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            hasSelectedReelsDestination = true,
            actionControls = ReelsActionControl.entries.toSet(),
        )

        assertFalse(InstagramReelsClassifier.isReelsActive(knownIdOnly))
        assertFalse(InstagramReelsClassifier.isReelsActive(selectedTabAndActionsWithoutPaging))
    }
}
