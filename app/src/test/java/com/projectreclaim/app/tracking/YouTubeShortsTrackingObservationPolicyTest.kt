package com.projectreclaim.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeShortsTrackingObservationPolicyTest {
    @Test
    fun `available YouTube root with active Shorts persists true`() {
        assertEquals(
            true,
            YouTubeShortsTrackingObservationPolicy.persistenceState(
                evidence = YouTubeShortsTrackingEvidence.YOUTUBE_ACCESSIBILITY_ROOT,
                detectorResult = true,
            ),
        )
    }

    @Test
    fun `available YouTube root without active Shorts persists false`() {
        assertEquals(
            false,
            YouTubeShortsTrackingObservationPolicy.persistenceState(
                evidence = YouTubeShortsTrackingEvidence.YOUTUBE_ACCESSIBILITY_ROOT,
                detectorResult = false,
            ),
        )
    }

    @Test
    fun `unavailable root produces no persistence observation`() {
        assertNull(
            YouTubeShortsTrackingObservationPolicy.persistenceState(
                evidence = YouTubeShortsTrackingEvidence.UNAVAILABLE_ROOT,
                detectorResult = false,
            ),
        )
        assertNull(
            YouTubeShortsTrackingObservationPolicy.persistenceState(
                evidence = YouTubeShortsTrackingEvidence.UNAVAILABLE_ROOT,
                detectorResult = true,
            ),
        )
    }

    @Test
    fun `known non YouTube transition persists false`() {
        assertEquals(
            false,
            YouTubeShortsTrackingObservationPolicy.persistenceState(
                evidence = YouTubeShortsTrackingEvidence.KNOWN_NON_YOUTUBE,
                detectorResult = true,
            ),
        )
    }
}
