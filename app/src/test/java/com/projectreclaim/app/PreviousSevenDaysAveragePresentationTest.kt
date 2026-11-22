package com.projectreclaim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviousSevenDaysAveragePresentationTest {
    @Test
    fun `available average shows short-form value and actual eligible day count`() {
        for (dayCount in 1..7) {
            val average = PreviousSevenDaysAverageState.Available(
                PreviousSevenDaysAverages(24 * 60_000L, 80 * 60_000L), dayCount,
            ).presentation()
            val days = if (dayCount == 1) "day" else "days"
            assertEquals("Daily average · previous $dayCount completed $days", average.label)
            assertEquals("24m", average.detail)
        }
    }

    @Test
    fun `available zero displays a measured duration`() {
        val average = PreviousSevenDaysAverageState.Available(PreviousSevenDaysAverages(0L, 0L), 1)
        assertEquals("0m", average.presentation().detail)
    }

    @Test
    fun `states without an average show an explanation instead of a duration or count`() {
        val expectedDetails = mapOf(
            PreviousSevenDaysAverageState.Loading to "Loading average…",
            PreviousSevenDaysAverageState.PermissionRequired to "Needs Usage Access.",
            PreviousSevenDaysAverageState.SetupRequired to "Finish setup, then wait for a completed day.",
            PreviousSevenDaysAverageState.NoCompletedDays to "Waiting for the first eligible completed day.",
            PreviousSevenDaysAverageState.Unavailable to "Average unavailable.",
        )
        expectedDetails.forEach { (state, detail) ->
            val presentation = state.presentation()
            assertEquals("Daily average · completed days", presentation.label)
            assertEquals(detail, presentation.detail)
        }
    }
}
