package com.projectreclaim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortFormTodayPresentationCalculatorTest {
    @Test
    fun `Accessibility enabled presents a complete short form total`() {
        assertEquals(
            ShortFormTodayPresentation.Complete(99_000L),
            ShortFormTodayPresentationCalculator.calculate(
                state = ShortFormTodayState.Available(99_000L),
                accessibilityServiceEnabled = true,
            ),
        )
    }

    @Test
    fun `Accessibility disabled presents saved short form time as partial`() {
        assertEquals(
            ShortFormTodayPresentation.Partial(99_000L),
            ShortFormTodayPresentationCalculator.calculate(
                state = ShortFormTodayState.Available(99_000L),
                accessibilityServiceEnabled = false,
            ),
        )
    }

    @Test
    fun `Accessibility disabled does not override missing Usage Access`() {
        assertEquals(
            ShortFormTodayPresentation.PermissionRequired,
            ShortFormTodayPresentationCalculator.calculate(
                state = ShortFormTodayState.PermissionRequired,
                accessibilityServiceEnabled = false,
            ),
        )
    }

    @Test
    fun `paused tracking qualifies zero instead of presenting it as authoritative`() {
        assertEquals(
            "0s - Tracking paused",
            TrackedUsageState.Available(0L).displayText(accessibilityServiceEnabled = false),
        )
    }
}
