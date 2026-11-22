package com.projectreclaim.app.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramContentStarvationWatchdogControllerTest {
    @Test
    fun `only explicit Instagram content events qualify`() {
        assertTrue(
            isInstagramContentStarvationWatchdogEvent(
                ReelsAccessibilityEventType.WINDOW_CONTENT_CHANGED,
                InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            ),
        )
        assertFalse(
            isInstagramContentStarvationWatchdogEvent(
                ReelsAccessibilityEventType.WINDOW_CONTENT_CHANGED,
                YouTubeShortsClassifier.YOUTUBE_PACKAGE,
            ),
        )
        assertFalse(
            isInstagramContentStarvationWatchdogEvent(
                ReelsAccessibilityEventType.WINDOW_CONTENT_CHANGED,
                null,
            ),
        )
        assertFalse(
            isInstagramContentStarvationWatchdogEvent(
                ReelsAccessibilityEventType.WINDOWS_CHANGED,
                InstagramReelsClassifier.INSTAGRAM_PACKAGE,
            ),
        )
    }

    @Test
    fun `first inactive Instagram content event arms at plus 1000 milliseconds`() {
        val decision = controller.onInstagramContentEvent(
            nowElapsedMillis = 2_000L,
            detectorActive = false,
        )!!

        assertEquals(decision.generation, decision.newWatchdogSchedule?.generation)
        assertEquals(3_000L, decision.newWatchdogSchedule?.deadlineElapsedMillis)
    }

    @Test
    fun `active detector does not arm an idle watchdog`() {
        assertNull(
            controller.onInstagramContentEvent(
                nowElapsedMillis = 2_000L,
                detectorActive = true,
            ),
        )
    }

    @Test
    fun `repeated events preserve generation and immutable deadline`() {
        val first = controller.event(now = 1_000L)
        val second = controller.event(now = 1_900L)

        assertEquals(first.generation, second.generation)
        assertEquals(2_000L, first.newWatchdogSchedule?.deadlineElapsedMillis)
        assertNull(second.newWatchdogSchedule)
    }

    @Test
    fun `matching trailing evaluation resets burst`() {
        val first = controller.event(now = 0L)

        assertTrue(controller.onTrailingContentEvaluationStarted(first.generation))
        assertFalse(
            controller.beginWatchdog(
                generation = first.generation,
                deadlineElapsedMillis = 1_000L,
                nowElapsedMillis = 1_000L,
            ),
        )
    }

    @Test
    fun `watchdog firing consumes burst before traversal`() {
        val first = controller.event(now = 0L)

        assertTrue(controller.begin(first, now = 1_000L))
        assertFalse(controller.begin(first, now = 1_000L))
    }

    @Test
    fun `continuing events after firing cannot rearm`() {
        val first = controller.event(now = 0L)
        assertTrue(controller.begin(first, now = 1_000L))

        repeat(100) { index ->
            val next = controller.event(now = 1_001L + index)
            assertEquals(first.generation, next.generation)
            assertNull(next.newWatchdogSchedule)
        }
    }

    @Test
    fun `stale generation cannot begin`() {
        val first = controller.event(now = 0L)

        assertFalse(
            controller.beginWatchdog(
                generation = first.generation + 1L,
                deadlineElapsedMillis = first.newWatchdogSchedule!!.deadlineElapsedMillis,
                nowElapsedMillis = 1_000L,
            ),
        )
    }

    @Test
    fun `window transition invalidates armed watchdog`() {
        val first = controller.event(now = 0L)

        controller.onWindowTransition()

        assertFalse(controller.begin(first, now = 1_000L))
    }

    @Test
    fun `lifecycle cancellation invalidates armed watchdog`() {
        val first = controller.event(now = 0L)

        controller.onLifecycleCancel()

        assertFalse(controller.begin(first, now = 1_000L))
    }

    @Test
    fun `post failure consumes without repost loop`() {
        val first = controller.event(now = 0L)

        assertTrue(controller.onWatchdogPostFailure(first.generation))
        repeat(100) { index ->
            val next = controller.event(now = 1L + index)
            assertEquals(first.generation, next.generation)
            assertNull(next.newWatchdogSchedule)
        }
    }

    @Test
    fun `quiet gap then new content creates new generation`() {
        val first = controller.event(now = 0L)
        assertTrue(controller.onTrailingContentEvaluationStarted(first.generation))

        val second = controller.event(now = 2_000L)

        assertNotEquals(first.generation, second.generation)
        assertEquals(3_000L, second.newWatchdogSchedule?.deadlineElapsedMillis)
    }

    @Test
    fun `exact deadline begin is accepted`() {
        val first = controller.event(now = 5_000L)

        assertTrue(controller.begin(first, now = 6_000L))
    }

    @Test
    fun `late begin is accepted once while generation remains valid`() {
        val first = controller.event(now = 5_000L)

        assertTrue(controller.begin(first, now = 50_000L))
        assertFalse(controller.begin(first, now = 50_001L))
    }

    @Test
    fun `begin before deadline is rejected without consuming`() {
        val first = controller.event(now = 5_000L)

        assertFalse(controller.begin(first, now = 5_999L))
        assertTrue(controller.begin(first, now = 6_000L))
    }

    @Test
    fun `old dequeued callback after new generation is rejected`() {
        val first = controller.event(now = 0L)
        assertTrue(controller.onTrailingContentEvaluationStarted(first.generation))
        val second = controller.event(now = 2_000L)

        assertFalse(controller.begin(first, now = 3_000L))
        assertTrue(controller.begin(second, now = 3_000L))
    }

    @Test
    fun `endless storm authorizes at most one watchdog`() {
        val first = controller.event(now = 0L)
        var authorized = 0

        for (now in 100L..60_000L step 100) {
            val event = controller.event(now)
            assertEquals(first.generation, event.generation)
            assertNull(event.newWatchdogSchedule)
            if (controller.begin(first, now)) authorized += 1
        }

        assertEquals(1, authorized)
    }

    @Test
    fun `authoritative active result consumes instead of resetting`() {
        val first = controller.event(now = 0L)

        assertTrue(controller.consume(first.generation))
        val continuing = controller.onInstagramContentEvent(
            nowElapsedMillis = 2_000L,
            detectorActive = true,
        )!!

        assertEquals(first.generation, continuing.generation)
        assertNull(continuing.newWatchdogSchedule)
    }

    @Test
    fun `trailing evaluation true consumes the reset burst`() {
        val first = controller.event(now = 0L)

        assertTrue(controller.onTrailingContentEvaluationStarted(first.generation))
        assertTrue(
            controller.onTrailingContentEvaluationFinished(
                generation = first.generation,
                detectorActive = true,
            ),
        )
        val continuing = controller.onInstagramContentEvent(
            nowElapsedMillis = 2_000L,
            detectorActive = true,
        )!!

        assertEquals(first.generation, continuing.generation)
        assertNull(continuing.newWatchdogSchedule)
    }

    @Test
    fun `trailing evaluation false leaves controller idle`() {
        val first = controller.event(now = 0L)

        assertTrue(controller.onTrailingContentEvaluationStarted(first.generation))
        assertFalse(
            controller.onTrailingContentEvaluationFinished(
                generation = first.generation,
                detectorActive = false,
            ),
        )
        val second = controller.event(now = 2_000L)

        assertNotEquals(first.generation, second.generation)
    }

    @Test
    fun `authoritative inactive transition permits a later burst`() {
        val first = controller.event(now = 0L)
        assertTrue(controller.begin(first, now = 1_000L))

        controller.onAuthoritativeInactiveTransition()
        val second = controller.event(now = 2_000L)

        assertNotEquals(first.generation, second.generation)
    }

    private val controller = InstagramContentStarvationWatchdogController()

    private fun InstagramContentStarvationWatchdogController.event(
        now: Long,
    ): InstagramContentStarvationEventDecision = onInstagramContentEvent(
        nowElapsedMillis = now,
        detectorActive = false,
    )!!

    private fun InstagramContentStarvationWatchdogController.begin(
        decision: InstagramContentStarvationEventDecision,
        now: Long,
    ): Boolean {
        val schedule = decision.newWatchdogSchedule
            ?: InstagramContentStarvationWatchdogSchedule(
                generation = decision.generation,
                deadlineElapsedMillis = now,
            )
        return beginWatchdog(
            generation = schedule.generation,
            deadlineElapsedMillis = schedule.deadlineElapsedMillis,
            nowElapsedMillis = now,
        )
    }
}
