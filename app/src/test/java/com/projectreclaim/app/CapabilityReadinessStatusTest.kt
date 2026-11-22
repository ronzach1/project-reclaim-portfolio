package com.projectreclaim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityReadinessStatusTest {
    @Test
    fun `unknown capability status is checking`() {
        assertEquals(
            CapabilityReadinessStatus.CHECKING,
            capabilityReadinessStatus(enabled = null),
        )
    }

    @Test
    fun `granted capability status is enabled`() {
        assertEquals(
            CapabilityReadinessStatus.ENABLED,
            capabilityReadinessStatus(enabled = true),
        )
    }

    @Test
    fun `missing capability status needs setup`() {
        assertEquals(
            CapabilityReadinessStatus.NEEDS_SETUP,
            capabilityReadinessStatus(enabled = false),
        )
    }
}
