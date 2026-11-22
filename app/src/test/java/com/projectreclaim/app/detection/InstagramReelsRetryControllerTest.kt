package com.projectreclaim.app.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramReelsRetryControllerTest {
    @Test
    fun `eligible Instagram false starts at absolute plus 400 milliseconds`() {
        val controller = InstagramReelsRetryController()

        val schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 10_000L,
        )

        assertEquals(10_400L, schedule?.deadlineElapsedMillis)
    }

    @Test
    fun `false retries advance through all fixed deadlines`() {
        val controller = InstagramReelsRetryController()
        var schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 1_000L,
        )!!

        assertEquals(1_400L, schedule.deadlineElapsedMillis)
        schedule = controller.runFalseRetry(schedule, now = 1_400L)!!
        assertEquals(2_400L, schedule.deadlineElapsedMillis)
        schedule = controller.runFalseRetry(schedule, now = 2_400L)!!
        assertEquals(4_400L, schedule.deadlineElapsedMillis)
        schedule = controller.runFalseRetry(schedule, now = 4_400L)!!
        assertEquals(8_900L, schedule.deadlineElapsedMillis)
        assertNull(controller.runFalseRetry(schedule, now = 8_900L))
    }

    @Test
    fun `unknown package retry continues to the next fixed deadline`() {
        val controller = InstagramReelsRetryController()
        val first = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 1_000L,
        )!!

        val next = controller.runRetry(
            schedule = first,
            result = UNKNOWN_PACKAGE,
            now = 1_400L,
        )

        assertEquals(2_400L, next?.deadlineElapsedMillis)
    }

    @Test
    fun `late unknown package retry keeps deadlines anchored to burst start`() {
        val controller = InstagramReelsRetryController()
        val first = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!

        val next = controller.runRetry(
            schedule = first,
            result = UNKNOWN_PACKAGE,
            now = 900L,
        )

        assertEquals(1_400L, next?.deadlineElapsedMillis)
    }

    @Test
    fun `late callback does not shift deadlines from burst start`() {
        val controller = InstagramReelsRetryController()
        val first = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!

        val next = controller.runFalseRetry(first, now = 900L)

        assertEquals(1_400L, next?.deadlineElapsedMillis)
    }

    @Test
    fun `late callback skips expired opportunities instead of catching up`() {
        val controller = InstagramReelsRetryController()
        val first = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!

        val next = controller.runFalseRetry(first, now = 3_500L)

        assertEquals(7_900L, next?.deadlineElapsedMillis)
    }

    @Test
    fun `earlier callback arriving at final deadline is rejected and exhausts burst`() {
        val controller = InstagramReelsRetryController()
        val first = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!

        assertFalse(
            controller.beginRetry(
                retryGeneration = first.generation,
                deadlineElapsedMillis = first.deadlineElapsedMillis,
                nowElapsedMillis = 7_900L,
            ),
        )
        assertFalse(
            controller.beginRetry(
                retryGeneration = first.generation,
                deadlineElapsedMillis = first.deadlineElapsedMillis,
                nowElapsedMillis = 8_000L,
            ),
        )
    }

    @Test
    fun `retry evaluations have a hard upper bound of four`() {
        val controller = InstagramReelsRetryController()
        var schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!
        var evaluations = 0

        while (true) {
            assertTrue(
                controller.beginRetry(
                    schedule.generation,
                    schedule.deadlineElapsedMillis,
                    schedule.deadlineElapsedMillis,
                ),
            )
            evaluations += 1
            val next = controller.onRetryEvaluation(
                retryGeneration = schedule.generation,
                result = INSTAGRAM_FALSE,
                nowElapsedMillis = schedule.deadlineElapsedMillis,
            ) ?: break
            schedule = next
        }

        assertEquals(4, evaluations)
        assertFalse(
            controller.beginRetry(
                schedule.generation,
                schedule.deadlineElapsedMillis,
                schedule.deadlineElapsedMillis,
            ),
        )
    }

    @Test
    fun `unknown package retries remain capped at four evaluations`() {
        val controller = InstagramReelsRetryController()
        var schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!
        var evaluations = 0

        while (true) {
            assertTrue(
                controller.beginRetry(
                    schedule.generation,
                    schedule.deadlineElapsedMillis,
                    schedule.deadlineElapsedMillis,
                ),
            )
            evaluations += 1
            val next = controller.onRetryEvaluation(
                retryGeneration = schedule.generation,
                result = UNKNOWN_PACKAGE,
                nowElapsedMillis = schedule.deadlineElapsedMillis,
            ) ?: break
            schedule = next
        }

        assertEquals(4, evaluations)
        assertFalse(
            controller.beginRetry(
                schedule.generation,
                schedule.deadlineElapsedMillis,
                schedule.deadlineElapsedMillis,
            ),
        )
    }

    @Test
    fun `final scheduled callback may execute with Handler lateness and then stops`() {
        val controller = InstagramReelsRetryController()
        var schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!
        schedule = controller.runFalseRetry(schedule, now = 400L)!!
        schedule = controller.runFalseRetry(schedule, now = 1_400L)!!
        schedule = controller.runFalseRetry(schedule, now = 3_400L)!!

        assertTrue(
            controller.beginRetry(
                retryGeneration = schedule.generation,
                deadlineElapsedMillis = schedule.deadlineElapsedMillis,
                nowElapsedMillis = 8_000L,
            ),
        )
        assertNull(
            controller.onRetryEvaluation(
                retryGeneration = schedule.generation,
                result = INSTAGRAM_FALSE,
                nowElapsedMillis = 8_000L,
            ),
        )
    }

    @Test
    fun `positive retry cancels immediately`() {
        val controller = InstagramReelsRetryController()
        val schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!
        assertTrue(
            controller.beginRetry(
                schedule.generation,
                schedule.deadlineElapsedMillis,
                schedule.deadlineElapsedMillis,
            ),
        )

        assertNull(
            controller.onRetryEvaluation(
                schedule.generation,
                INSTAGRAM_TRUE,
                schedule.deadlineElapsedMillis,
            ),
        )
        assertFalse(
            controller.beginRetry(
                schedule.generation,
                schedule.deadlineElapsedMillis,
                schedule.deadlineElapsedMillis,
            ),
        )
    }

    @Test
    fun `known non Instagram normal result beats Instagram hint and cancels`() {
        val controller = InstagramReelsRetryController()
        val oldSchedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!

        val replacement = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = KNOWN_NON_INSTAGRAM,
            now = 100L,
            hasInstagramHint = true,
        )

        assertNull(replacement)
        assertFalse(
            controller.beginRetry(
                oldSchedule.generation,
                oldSchedule.deadlineElapsedMillis,
                oldSchedule.deadlineElapsedMillis,
            ),
        )
    }

    @Test
    fun `known non Instagram retry cancels immediately`() {
        val controller = InstagramReelsRetryController()
        val schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!
        assertTrue(
            controller.beginRetry(
                schedule.generation,
                schedule.deadlineElapsedMillis,
                schedule.deadlineElapsedMillis,
            ),
        )

        assertNull(
            controller.onRetryEvaluation(
                schedule.generation,
                KNOWN_NON_INSTAGRAM,
                schedule.deadlineElapsedMillis,
            ),
        )
    }

    @Test
    fun `new eligible transition supersedes the previous generation`() {
        val controller = InstagramReelsRetryController()
        val oldSchedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!

        val newSchedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOWS_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 100L,
        )!!

        assertNotEquals(oldSchedule.generation, newSchedule.generation)
        assertEquals(500L, newSchedule.deadlineElapsedMillis)
        assertFalse(
            controller.beginRetry(
                oldSchedule.generation,
                oldSchedule.deadlineElapsedMillis,
                oldSchedule.deadlineElapsedMillis,
            ),
        )
        assertTrue(
            controller.beginRetry(
                newSchedule.generation,
                newSchedule.deadlineElapsedMillis,
                newSchedule.deadlineElapsedMillis,
            ),
        )
    }

    @Test
    fun `content false does not restart or extend active burst`() {
        val controller = InstagramReelsRetryController()
        val schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 1_000L,
        )!!

        assertNull(
            controller.normal(
                origin = InstagramReelsEvaluationOrigin.CONTENT_CHANGED,
                result = INSTAGRAM_FALSE,
                now = 1_200L,
            ),
        )
        assertTrue(
            controller.beginRetry(
                schedule.generation,
                schedule.deadlineElapsedMillis,
                schedule.deadlineElapsedMillis,
            ),
        )
    }

    @Test
    fun `content unavailable does not restart or extend active burst`() {
        val controller = InstagramReelsRetryController()
        val schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 1_000L,
        )!!

        assertNull(
            controller.normal(
                origin = InstagramReelsEvaluationOrigin.CONTENT_CHANGED,
                result = ROOT_UNAVAILABLE,
                now = 1_200L,
                hasInstagramHint = true,
            ),
        )
        assertTrue(
            controller.beginRetry(
                schedule.generation,
                schedule.deadlineElapsedMillis,
                schedule.deadlineElapsedMillis,
            ),
        )
    }

    @Test
    fun `content unknown package does not restart or extend active burst`() {
        val controller = InstagramReelsRetryController()
        val schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 1_000L,
        )!!

        assertNull(
            controller.normal(
                origin = InstagramReelsEvaluationOrigin.CONTENT_CHANGED,
                result = UNKNOWN_PACKAGE,
                now = 1_200L,
                hasInstagramHint = true,
            ),
        )
        assertTrue(
            controller.beginRetry(
                schedule.generation,
                schedule.deadlineElapsedMillis,
                schedule.deadlineElapsedMillis,
            ),
        )
    }

    @Test
    fun `content true cancels active burst`() {
        val controller = InstagramReelsRetryController()
        val schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!

        assertNull(
            controller.normal(
                origin = InstagramReelsEvaluationOrigin.CONTENT_CHANGED,
                result = INSTAGRAM_TRUE,
                now = 100L,
            ),
        )
        assertFalse(
            controller.beginRetry(
                schedule.generation,
                schedule.deadlineElapsedMillis,
                schedule.deadlineElapsedMillis,
            ),
        )
    }

    @Test
    fun `initial positive does not create a burst`() {
        val controller = InstagramReelsRetryController()

        assertNull(
            controller.normal(
                origin = InstagramReelsEvaluationOrigin.SERVICE_CONNECTED,
                result = INSTAGRAM_TRUE,
                now = 0L,
            ),
        )
    }

    @Test
    fun `unavailable Instagram relevant transition starts a burst`() {
        val controller = InstagramReelsRetryController()

        val schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOWS_CHANGED,
            result = ROOT_UNAVAILABLE,
            now = 2_000L,
            hasInstagramHint = true,
        )

        assertEquals(2_400L, schedule?.deadlineElapsedMillis)
    }

    @Test
    fun `Instagram hinted window state with unknown package starts a burst`() {
        val controller = InstagramReelsRetryController()

        val schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = UNKNOWN_PACKAGE,
            now = 2_000L,
            hasInstagramHint = true,
        )

        assertEquals(2_400L, schedule?.deadlineElapsedMillis)
    }

    @Test
    fun `Instagram hinted windows changed with unknown package starts a burst`() {
        val controller = InstagramReelsRetryController()

        val schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOWS_CHANGED,
            result = UNKNOWN_PACKAGE,
            now = 2_000L,
            hasInstagramHint = true,
        )

        assertEquals(2_400L, schedule?.deadlineElapsedMillis)
    }

    @Test
    fun `unavailable service connected evaluation starts without a hint`() {
        val controller = InstagramReelsRetryController()

        val schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.SERVICE_CONNECTED,
            result = ROOT_UNAVAILABLE,
            now = 2_000L,
        )

        assertEquals(2_400L, schedule?.deadlineElapsedMillis)
    }

    @Test
    fun `ordinary content false cannot start a burst`() {
        val controller = InstagramReelsRetryController()

        assertNull(
            controller.normal(
                origin = InstagramReelsEvaluationOrigin.CONTENT_CHANGED,
                result = INSTAGRAM_FALSE,
                now = 0L,
            ),
        )
    }

    @Test
    fun `content unknown package cannot start a burst`() {
        val controller = InstagramReelsRetryController()

        assertNull(
            controller.normal(
                origin = InstagramReelsEvaluationOrigin.CONTENT_CHANGED,
                result = UNKNOWN_PACKAGE,
                now = 0L,
                hasInstagramHint = true,
            ),
        )
    }

    @Test
    fun `service connected unknown package without hint does not start a burst`() {
        val controller = InstagramReelsRetryController()

        assertNull(
            controller.normal(
                origin = InstagramReelsEvaluationOrigin.SERVICE_CONNECTED,
                result = UNKNOWN_PACKAGE,
                now = 0L,
            ),
        )
    }

    @Test
    fun `content starvation watchdog Instagram false starts when idle`() {
        val controller = InstagramReelsRetryController()

        val schedule = controller.watchdog(
            result = INSTAGRAM_FALSE,
            now = 2_000L,
        )

        assertEquals(2_400L, schedule?.deadlineElapsedMillis)
    }

    @Test
    fun `content starvation watchdog unavailable with Instagram hint starts when idle`() {
        val controller = InstagramReelsRetryController()

        val schedule = controller.watchdog(
            result = ROOT_UNAVAILABLE,
            now = 2_000L,
            hasInstagramHint = true,
        )

        assertEquals(2_400L, schedule?.deadlineElapsedMillis)
    }

    @Test
    fun `content starvation watchdog unknown package with Instagram hint starts when idle`() {
        val controller = InstagramReelsRetryController()

        val schedule = controller.watchdog(
            result = UNKNOWN_PACKAGE,
            now = 2_000L,
            hasInstagramHint = true,
        )

        assertEquals(2_400L, schedule?.deadlineElapsedMillis)
    }

    @Test
    fun `content starvation watchdog unavailable or unknown without hint cannot start`() {
        val controller = InstagramReelsRetryController()

        assertNull(
            controller.watchdog(
                result = ROOT_UNAVAILABLE,
                now = 2_000L,
                hasInstagramHint = false,
            ),
        )
        assertNull(
            controller.watchdog(
                result = UNKNOWN_PACKAGE,
                now = 2_000L,
                hasInstagramHint = false,
            ),
        )
    }

    @Test
    fun `watchdog origin cannot use normal superseding API`() {
        val controller = InstagramReelsRetryController()
        val existing = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!

        assertNull(
            controller.normal(
                origin = InstagramReelsEvaluationOrigin.CONTENT_STARVATION_WATCHDOG,
                result = INSTAGRAM_FALSE,
                now = 100L,
            ),
        )
        assertTrue(
            controller.beginRetry(
                existing.generation,
                existing.deadlineElapsedMillis,
                existing.deadlineElapsedMillis,
            ),
        )
    }

    @Test
    fun `content starvation watchdog failure leaves active retry generation and deadlines unchanged`() {
        val controller = InstagramReelsRetryController()
        val existing = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!

        assertNull(
            controller.watchdog(
                result = INSTAGRAM_FALSE,
                now = 100L,
            ),
        )
        assertTrue(
            controller.beginRetry(
                retryGeneration = existing.generation,
                deadlineElapsedMillis = existing.deadlineElapsedMillis,
                nowElapsedMillis = 400L,
            ),
        )
        val next = controller.onRetryEvaluation(
            retryGeneration = existing.generation,
            result = INSTAGRAM_FALSE,
            nowElapsedMillis = 400L,
        )
        assertEquals(existing.generation, next?.generation)
        assertEquals(1_400L, next?.deadlineElapsedMillis)
    }

    @Test
    fun `content starvation watchdog true cancels active retry`() {
        val controller = InstagramReelsRetryController()
        val existing = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!

        assertNull(controller.watchdog(result = INSTAGRAM_TRUE, now = 100L))
        assertFalse(
            controller.beginRetry(
                existing.generation,
                existing.deadlineElapsedMillis,
                existing.deadlineElapsedMillis,
            ),
        )
    }

    @Test
    fun `content starvation watchdog known non Instagram cancels active retry`() {
        val controller = InstagramReelsRetryController()
        val existing = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!

        assertNull(
            controller.watchdog(
                result = KNOWN_NON_INSTAGRAM,
                now = 100L,
                hasInstagramHint = true,
            ),
        )
        assertFalse(
            controller.beginRetry(
                existing.generation,
                existing.deadlineElapsedMillis,
                existing.deadlineElapsedMillis,
            ),
        )
    }

    @Test
    fun `content starvation watchdog retry remains capped at four evaluations`() {
        val controller = InstagramReelsRetryController()
        var schedule = controller.watchdog(
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!
        var evaluations = 0

        while (true) {
            assertTrue(
                controller.beginRetry(
                    schedule.generation,
                    schedule.deadlineElapsedMillis,
                    schedule.deadlineElapsedMillis,
                ),
            )
            evaluations += 1
            val next = controller.onRetryEvaluation(
                retryGeneration = schedule.generation,
                result = INSTAGRAM_FALSE,
                nowElapsedMillis = schedule.deadlineElapsedMillis,
            ) ?: break
            schedule = next
        }

        assertEquals(4, evaluations)
    }

    @Test
    fun `post failure abandonment invalidates scheduled generation`() {
        val controller = InstagramReelsRetryController()
        val schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!

        controller.abandon(schedule.generation)

        assertFalse(
            controller.beginRetry(
                schedule.generation,
                schedule.deadlineElapsedMillis,
                schedule.deadlineElapsedMillis,
            ),
        )
    }

    @Test
    fun `common cancellation invalidates scheduled generation`() {
        val controller = InstagramReelsRetryController()
        val schedule = controller.normal(
            origin = InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED,
            result = INSTAGRAM_FALSE,
            now = 0L,
        )!!

        controller.cancel()

        assertFalse(
            controller.beginRetry(
                schedule.generation,
                schedule.deadlineElapsedMillis,
                schedule.deadlineElapsedMillis,
            ),
        )
    }

    private fun InstagramReelsRetryController.normal(
        origin: InstagramReelsEvaluationOrigin,
        result: InstagramReelsEvaluationResult,
        now: Long,
        hasInstagramHint: Boolean = false,
    ): InstagramReelsRetrySchedule? = onNormalEvaluation(
        origin = origin,
        hasInstagramPackageHint = hasInstagramHint,
        result = result,
        nowElapsedMillis = now,
    )

    private fun InstagramReelsRetryController.runFalseRetry(
        schedule: InstagramReelsRetrySchedule,
        now: Long,
    ): InstagramReelsRetrySchedule? = runRetry(schedule, INSTAGRAM_FALSE, now)

    private fun InstagramReelsRetryController.watchdog(
        result: InstagramReelsEvaluationResult,
        now: Long,
        hasInstagramHint: Boolean = true,
    ): InstagramReelsRetrySchedule? = onContentStarvationWatchdogEvaluation(
        hasInstagramPackageHint = hasInstagramHint,
        result = result,
        nowElapsedMillis = now,
    )

    private fun InstagramReelsRetryController.runRetry(
        schedule: InstagramReelsRetrySchedule,
        result: InstagramReelsEvaluationResult,
        now: Long,
    ): InstagramReelsRetrySchedule? {
        assertTrue(
            beginRetry(
                schedule.generation,
                schedule.deadlineElapsedMillis,
                now,
            ),
        )
        return onRetryEvaluation(schedule.generation, result, now)
    }

    private companion object {
        val INSTAGRAM_FALSE = InstagramReelsEvaluationResult(
            rootEvidence = InstagramReelsRootEvidence.INSTAGRAM,
            detectorResult = false,
        )
        val INSTAGRAM_TRUE = InstagramReelsEvaluationResult(
            rootEvidence = InstagramReelsRootEvidence.INSTAGRAM,
            detectorResult = true,
        )
        val ROOT_UNAVAILABLE = InstagramReelsEvaluationResult(
            rootEvidence = InstagramReelsRootEvidence.UNAVAILABLE,
            detectorResult = false,
        )
        val KNOWN_NON_INSTAGRAM = InstagramReelsEvaluationResult(
            rootEvidence = InstagramReelsRootEvidence.KNOWN_NON_INSTAGRAM,
            detectorResult = false,
        )
        val UNKNOWN_PACKAGE = InstagramReelsEvaluationResult(
            rootEvidence = InstagramReelsRootEvidence.UNKNOWN_PACKAGE,
            detectorResult = false,
        )
    }
}
