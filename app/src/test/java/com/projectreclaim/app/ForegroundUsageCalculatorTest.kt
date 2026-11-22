package com.projectreclaim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundUsageCalculatorTest {
    @Test
    fun `calculator computes a supplied range only from lifecycle events provided`() {
        val range = CalendarDayUsageTime.history(
            java.time.Clock.fixed(
                java.time.Instant.parse("2026-08-25T08:15:00Z"),
                java.time.ZoneId.of("Asia/Jerusalem"),
            ),
        )[1].range
        val usage = ForegroundUsageCalculator.calculate(
            events = listOf(
                resumed(INSTAGRAM, "ActivityA", range.startMillis - 90L),
                paused(INSTAGRAM, "ActivityA", range.startMillis - 80L),
                resumed(INSTAGRAM, "ActivityA", range.startMillis + 10L),
                paused(INSTAGRAM, "ActivityA", range.startMillis + 30L),
                resumed(INSTAGRAM, "ActivityA", range.endMillis + 10L),
                paused(INSTAGRAM, "ActivityA", range.endMillis + 20L),
            ),
            supportedPackages = setOf(INSTAGRAM),
            range = range,
        )

        assertEquals(20L, usage.getValue(INSTAGRAM))
    }

    @Test
    fun `historical range reconstructs foreground carried across its start boundary`() {
        val range = CalendarDayUsageTime.history(
            java.time.Clock.fixed(
                java.time.Instant.parse("2026-08-25T08:15:00Z"),
                java.time.ZoneId.of("Asia/Jerusalem"),
            ),
        )[1].range
        val usage = ForegroundUsageCalculator.calculate(
            events = listOf(
                resumed(INSTAGRAM, "ActivityA", range.startMillis - 10L),
                paused(INSTAGRAM, "ActivityA", range.startMillis + 25L),
            ),
            supportedPackages = setOf(INSTAGRAM),
            range = range,
        )

        assertEquals(25L, usage.getValue(INSTAGRAM))
    }

    @Test
    fun `session fully today`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", minute(10 * 60)),
            paused(INSTAGRAM, "ActivityA", minute(10 * 60 + 5)),
            now = minute(12 * 60),
        )

        assertEquals(minute(5), usage.getValue(INSTAGRAM))
    }

    @Test
    fun `yesterday usage does not leak into today`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", -minute(60)),
            paused(INSTAGRAM, "ActivityA", -minute(55)),
            now = minute(12 * 60),
        )

        assertEquals(0L, usage.getValue(INSTAGRAM))
    }

    @Test
    fun `session crossing midnight counts only today`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", -minute(2)),
            paused(INSTAGRAM, "ActivityA", minute(7)),
            now = minute(12 * 60),
        )

        assertEquals(minute(7), usage.getValue(INSTAGRAM))
    }

    @Test
    fun `foreground app is closed at now`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", minute(10 * 60)),
            now = minute(10 * 60 + 12),
        )

        assertEquals(minute(12), usage.getValue(INSTAGRAM))
    }

    @Test
    fun `multiple activity handoff is one continuous package interval`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", minute(10 * 60)),
            resumed(INSTAGRAM, "ActivityB", minute(10 * 60 + 1)),
            paused(INSTAGRAM, "ActivityA", minute(10 * 60 + 2)),
            paused(INSTAGRAM, "ActivityB", minute(10 * 60 + 5)),
            now = minute(12 * 60),
        )

        assertEquals(minute(5), usage.getValue(INSTAGRAM))
    }

    @Test
    fun `same class overlapping instances remain active until final pause`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", minute(10 * 60)),
            resumed(INSTAGRAM, "ActivityA", minute(10 * 60 + 1)),
            paused(INSTAGRAM, "ActivityA", minute(10 * 60 + 5)),
            paused(INSTAGRAM, "ActivityA", minute(10 * 60 + 6)),
            now = minute(12 * 60),
        )

        assertEquals(minute(6), usage.getValue(INSTAGRAM))
    }

    @Test
    fun `unmatched known class pause is a no-op`() {
        val usage = calculate(
            paused(INSTAGRAM, "ActivityA", minute(9 * 60 + 59)),
            resumed(INSTAGRAM, "ActivityA", minute(10 * 60)),
            paused(INSTAGRAM, "ActivityA", minute(10 * 60 + 5)),
            now = minute(12 * 60),
        )

        assertEquals(minute(5), usage.getValue(INSTAGRAM))
    }

    @Test
    fun `unsupported package is ignored`() {
        val usage = calculate(
            resumed("com.example.unsupported", "ActivityA", minute(10 * 60)),
            paused("com.example.unsupported", "ActivityA", minute(10 * 60 + 5)),
            now = minute(12 * 60),
        )

        assertEquals(0L, usage.getValue(INSTAGRAM))
        assertEquals(setOf(INSTAGRAM), usage.keys)
    }

    @Test
    fun `X foreground usage is calculated when it is supported`() {
        val usage = ForegroundUsageCalculator.calculate(
            events = listOf(
                resumed(X_PACKAGE_NAME, "MainActivity", 10L),
                paused(X_PACKAGE_NAME, "MainActivity", 40L),
            ),
            supportedPackages = SUPPORTED_SOCIAL_PACKAGE_NAMES,
            range = UsageTimeRange(startMillis = 0L, endMillis = 50L),
        )

        assertEquals(30L, usage.getValue(X_PACKAGE_NAME))
        assertEquals(0L, usage.getValue(INSTAGRAM_PACKAGE_NAME))
    }

    @Test
    fun `shutdown clears foreground state`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", minute(10 * 60)),
            deviceEvent(UsageLifecycleEventType.DEVICE_SHUTDOWN, minute(10 * 60 + 5)),
            deviceEvent(UsageLifecycleEventType.DEVICE_STARTUP, minute(10 * 60 + 6)),
            now = minute(10 * 60 + 12),
        )

        assertEquals(minute(5), usage.getValue(INSTAGRAM))
    }

    @Test
    fun `sequential reuse of same class produces separate intervals`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", minute(10 * 60)),
            paused(INSTAGRAM, "ActivityA", minute(10 * 60 + 5)),
            resumed(INSTAGRAM, "ActivityA", minute(10 * 60 + 10)),
            paused(INSTAGRAM, "ActivityA", minute(10 * 60 + 13)),
            now = minute(12 * 60),
        )

        assertEquals(minute(8), usage.getValue(INSTAGRAM))
    }

    @Test
    fun `overlapping null class instances remain active until final pause`() {
        val usage = calculate(
            resumed(INSTAGRAM, null, minute(10 * 60)),
            resumed(INSTAGRAM, null, minute(10 * 60 + 1)),
            paused(INSTAGRAM, null, minute(10 * 60 + 5)),
            paused(INSTAGRAM, null, minute(10 * 60 + 6)),
            now = minute(12 * 60),
        )

        assertEquals(minute(6), usage.getValue(INSTAGRAM))
    }

    @Test
    fun `null pause does not clear known class state`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", minute(10 * 60)),
            resumed(INSTAGRAM, null, minute(10 * 60 + 1)),
            paused(INSTAGRAM, null, minute(10 * 60 + 5)),
            paused(INSTAGRAM, "ActivityA", minute(10 * 60 + 6)),
            now = minute(12 * 60),
        )

        assertEquals(minute(6), usage.getValue(INSTAGRAM))
    }

    @Test
    fun `known class pause does not clear null class state`() {
        val usage = calculate(
            resumed(INSTAGRAM, null, minute(10 * 60)),
            resumed(INSTAGRAM, "ActivityA", minute(10 * 60 + 1)),
            paused(INSTAGRAM, "ActivityA", minute(10 * 60 + 5)),
            paused(INSTAGRAM, null, minute(10 * 60 + 6)),
            now = minute(12 * 60),
        )

        assertEquals(minute(6), usage.getValue(INSTAGRAM))
    }

    @Test
    fun `unmatched null pause is a no-op`() {
        val usage = calculate(
            paused(INSTAGRAM, null, minute(9 * 60 + 59)),
            resumed(INSTAGRAM, null, minute(10 * 60)),
            paused(INSTAGRAM, null, minute(10 * 60 + 5)),
            now = minute(12 * 60),
        )

        assertEquals(minute(5), usage.getValue(INSTAGRAM))
    }

    @Test
    fun `resume exactly at range start counts from start`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", 0L),
            paused(INSTAGRAM, "ActivityA", minute(5)),
            now = minute(10),
        )

        assertEquals(minute(5), usage.getValue(INSTAGRAM))
    }

    @Test
    fun `pause exactly at range start closes carry in with zero duration`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", -minute(2)),
            paused(INSTAGRAM, "ActivityA", 0L),
            now = minute(10),
        )

        assertEquals(0L, usage.getValue(INSTAGRAM))
    }

    @Test
    fun `event exactly at range end is excluded`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", minute(10)),
            now = minute(10),
        )

        assertEquals(0L, usage.getValue(INSTAGRAM))
    }

    @Test
    fun `equal timestamp handoff between distinct activities preserves duration`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", minute(10 * 60)),
            paused(INSTAGRAM, "ActivityA", minute(10 * 60 + 5)),
            resumed(INSTAGRAM, "ActivityB", minute(10 * 60 + 5)),
            paused(INSTAGRAM, "ActivityB", minute(10 * 60 + 10)),
            now = minute(12 * 60),
        )

        assertEquals(minute(10), usage.getValue(INSTAGRAM))
    }

    @Test
    fun `normal paused then stopped lifecycle closes only at pause`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", 10L),
            paused(INSTAGRAM, "ActivityA", 15L),
            stopped(INSTAGRAM, "ActivityA", 20L),
            now = 30L,
        )

        assertEquals(5L, usage.getValue(INSTAGRAM))
    }

    @Test
    fun `stopped closes active activity when pause is missing`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", 10L),
            stopped(INSTAGRAM, "ActivityA", 15L),
            now = 30L,
        )

        assertEquals(5L, usage.getValue(INSTAGRAM))
    }

    @Test
    fun `stopped after paused and resumed same class closes active foreground`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", 1L),
            paused(INSTAGRAM, "ActivityA", 2L),
            resumed(INSTAGRAM, "ActivityA", 3L),
            stopped(INSTAGRAM, "ActivityA", 4L),
            now = 100L,
        )

        assertEquals(2L, usage.getValue(INSTAGRAM))
    }

    @Test
    fun `ambiguous same class stopped favors ending active foreground`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", 1L),
            resumed(INSTAGRAM, "ActivityA", 2L),
            paused(INSTAGRAM, "ActivityA", 5L),
            stopped(INSTAGRAM, "ActivityA", 6L),
            paused(INSTAGRAM, "ActivityA", 10L),
            stopped(INSTAGRAM, "ActivityA", 11L),
            now = 20L,
        )

        assertEquals(5L, usage.getValue(INSTAGRAM))
    }

    @Test
    fun `stopped after paused and resumed null class closes active foreground`() {
        val usage = calculate(
            resumed(INSTAGRAM, null, 1L),
            paused(INSTAGRAM, null, 2L),
            resumed(INSTAGRAM, null, 3L),
            stopped(INSTAGRAM, null, 4L),
            now = 100L,
        )

        assertEquals(2L, usage.getValue(INSTAGRAM))
    }

    @Test
    fun `reproduced pause gap shape does not remain foreground to range end`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", 1L),
            paused(INSTAGRAM, "ActivityA", 2L),
            resumed(INSTAGRAM, "ActivityA", 3L),
            stopped(INSTAGRAM, "ActivityA", 4L),
            stopped(INSTAGRAM, "ActivityA", 5L),
            now = 100L,
        )

        assertEquals(2L, usage.getValue(INSTAGRAM))
    }

    @Test
    fun `stopped lifecycle remains scoped across overlapping activity classes`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", 1L),
            resumed(INSTAGRAM, "ActivityB", 2L),
            paused(INSTAGRAM, "ActivityA", 5L),
            stopped(INSTAGRAM, "ActivityA", 6L),
            stopped(INSTAGRAM, "ActivityB", 10L),
            now = 20L,
        )

        assertEquals(9L, usage.getValue(INSTAGRAM))
    }

    @Test
    fun `unmatched stopped is a no op and does not corrupt later session`() {
        val usage = calculate(
            stopped(INSTAGRAM, "ActivityA", 1L),
            resumed(INSTAGRAM, "ActivityA", 10L),
            stopped(INSTAGRAM, "ActivityA", 15L),
            now = 20L,
        )

        assertEquals(5L, usage.getValue(INSTAGRAM))
    }

    @Test
    fun `stopped fallback safely closes null class activity`() {
        val usage = calculate(
            resumed(INSTAGRAM, null, 10L),
            stopped(INSTAGRAM, null, 15L),
            now = 20L,
        )

        assertEquals(5L, usage.getValue(INSTAGRAM))
    }

    @Test
    fun `paused carry in stop does not close later active activity`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", -10L),
            paused(INSTAGRAM, "ActivityA", -5L),
            stopped(INSTAGRAM, "ActivityA", 2L),
            resumed(INSTAGRAM, "ActivityA", 3L),
            stopped(INSTAGRAM, "ActivityA", 6L),
            now = 10L,
        )

        assertEquals(3L, usage.getValue(INSTAGRAM))
    }

    @Test
    fun `device reset clears paused awaiting stop state`() {
        val usage = calculate(
            resumed(INSTAGRAM, "ActivityA", 1L),
            paused(INSTAGRAM, "ActivityA", 2L),
            deviceEvent(UsageLifecycleEventType.DEVICE_SHUTDOWN, 3L),
            deviceEvent(UsageLifecycleEventType.DEVICE_STARTUP, 4L),
            resumed(INSTAGRAM, "ActivityA", 5L),
            stopped(INSTAGRAM, "ActivityA", 6L),
            now = 10L,
        )

        assertEquals(2L, usage.getValue(INSTAGRAM))
    }

    private fun calculate(
        vararg events: UsageLifecycleEvent,
        now: Long,
    ): Map<String, Long> = ForegroundUsageCalculator.calculate(
        events = events.toList(),
        supportedPackages = setOf(INSTAGRAM),
        range = UsageTimeRange(startMillis = 0L, endMillis = now),
    )

    private fun resumed(packageName: String, activityId: String?, timestamp: Long) =
        UsageLifecycleEvent(
            packageName = packageName,
            activityId = activityId,
            type = UsageLifecycleEventType.RESUMED,
            timestampMillis = timestamp,
        )

    private fun paused(packageName: String, activityId: String?, timestamp: Long) =
        UsageLifecycleEvent(
            packageName = packageName,
            activityId = activityId,
            type = UsageLifecycleEventType.PAUSED,
            timestampMillis = timestamp,
        )

    private fun stopped(packageName: String, activityId: String?, timestamp: Long) =
        UsageLifecycleEvent(
            packageName = packageName,
            activityId = activityId,
            type = UsageLifecycleEventType.STOPPED,
            timestampMillis = timestamp,
        )

    private fun deviceEvent(type: UsageLifecycleEventType, timestamp: Long) =
        UsageLifecycleEvent(
            packageName = null,
            activityId = null,
            type = type,
            timestampMillis = timestamp,
        )

    private fun minute(value: Int): Long = value * 60_000L

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
    }
}
