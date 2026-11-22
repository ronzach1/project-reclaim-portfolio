package com.projectreclaim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FullMeasurementSetupStartedAtTest {
    @Test
    fun `records first time both capabilities are enabled and does not reset it`() {
        assertEquals(
            null,
            FullMeasurementSetupStartedAt.resolve(null, true, false, 100L),
        )
        assertEquals(
            200L,
            FullMeasurementSetupStartedAt.resolve(null, true, true, 200L),
        )
        assertEquals(
            200L,
            FullMeasurementSetupStartedAt.resolve(200L, false, false, 300L),
        )
    }
}
