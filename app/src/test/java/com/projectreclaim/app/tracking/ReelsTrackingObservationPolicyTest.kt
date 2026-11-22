package com.projectreclaim.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReelsTrackingObservationPolicyTest {
    @Test
    fun `detector result with accessibility root is authoritative`() {
        assertEquals(
            true,
            ReelsTrackingObservationPolicy.persistenceState(
                evidence = ReelsTrackingEvidence.ACCESSIBILITY_ROOT,
                detectorResult = true,
            ),
        )
        assertEquals(
            false,
            ReelsTrackingObservationPolicy.persistenceState(
                evidence = ReelsTrackingEvidence.ACCESSIBILITY_ROOT,
                detectorResult = false,
            ),
        )
    }

    @Test
    fun `detector result without accessibility root is not authoritative`() {
        assertNull(
            ReelsTrackingObservationPolicy.persistenceState(
                evidence = ReelsTrackingEvidence.UNAVAILABLE_ROOT,
                detectorResult = false,
            ),
        )
    }

    @Test
    fun `known non Instagram transition remains an authoritative false`() {
        assertEquals(
            false,
            ReelsTrackingObservationPolicy.persistenceState(
                evidence = ReelsTrackingEvidence.KNOWN_NON_INSTAGRAM,
                detectorResult = true,
            ),
        )
    }
}
