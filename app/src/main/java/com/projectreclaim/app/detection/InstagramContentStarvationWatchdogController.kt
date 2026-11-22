package com.projectreclaim.app.detection

internal fun isInstagramContentStarvationWatchdogEvent(
    eventType: ReelsAccessibilityEventType,
    packageName: String?,
): Boolean =
    eventType == ReelsAccessibilityEventType.WINDOW_CONTENT_CHANGED &&
        packageName == InstagramReelsClassifier.INSTAGRAM_PACKAGE

data class InstagramContentStarvationWatchdogSchedule(
    val generation: Long,
    val deadlineElapsedMillis: Long,
)

data class InstagramContentStarvationEventDecision(
    val generation: Long,
    val newWatchdogSchedule: InstagramContentStarvationWatchdogSchedule?,
)

/**
 * Owns one immutable watchdog opportunity per continuous Instagram content burst.
 * Android event delivery and Handler scheduling remain responsibilities of the service.
 */
class InstagramContentStarvationWatchdogController {
    private var nextGeneration = 0L
    private var state: State = State.Idle
    private var trailingEvaluationGeneration: Long? = null

    fun onInstagramContentEvent(
        nowElapsedMillis: Long,
        detectorActive: Boolean,
    ): InstagramContentStarvationEventDecision? = when (val current = state) {
        State.Idle -> {
            if (detectorActive) {
                null
            } else {
                trailingEvaluationGeneration = null
                nextGeneration += 1L
                val schedule = InstagramContentStarvationWatchdogSchedule(
                    generation = nextGeneration,
                    deadlineElapsedMillis = nowElapsedMillis + WATCHDOG_DELAY_MILLIS,
                )
                state = State.Armed(
                    generation = schedule.generation,
                    firstEventElapsedMillis = nowElapsedMillis,
                    deadlineElapsedMillis = schedule.deadlineElapsedMillis,
                )
                InstagramContentStarvationEventDecision(
                    generation = schedule.generation,
                    newWatchdogSchedule = schedule,
                )
            }
        }

        is State.Armed -> InstagramContentStarvationEventDecision(
            generation = current.generation,
            newWatchdogSchedule = null,
        )

        is State.Consumed -> InstagramContentStarvationEventDecision(
            generation = current.generation,
            newWatchdogSchedule = null,
        )
    }

    fun beginWatchdog(
        generation: Long,
        deadlineElapsedMillis: Long,
        nowElapsedMillis: Long,
    ): Boolean {
        val current = state as? State.Armed ?: return false
        if (current.generation != generation) return false
        if (current.deadlineElapsedMillis != deadlineElapsedMillis) return false
        if (nowElapsedMillis < deadlineElapsedMillis) return false

        state = State.Consumed(generation)
        return true
    }

    fun onTrailingContentEvaluationStarted(generation: Long): Boolean {
        if (state.generation != generation) return false
        state = State.Idle
        trailingEvaluationGeneration = generation
        return true
    }

    fun onTrailingContentEvaluationFinished(
        generation: Long,
        detectorActive: Boolean,
    ): Boolean {
        if (trailingEvaluationGeneration != generation) return false
        trailingEvaluationGeneration = null
        if (!detectorActive || state != State.Idle) return false
        state = State.Consumed(generation)
        return true
    }

    fun consume(generation: Long): Boolean {
        val current = state as? State.Armed ?: return false
        if (current.generation != generation) return false
        state = State.Consumed(generation)
        return true
    }

    fun onWatchdogPostFailure(generation: Long): Boolean = consume(generation)

    fun onWindowTransition() {
        state = State.Idle
        trailingEvaluationGeneration = null
    }

    fun onLifecycleCancel() {
        state = State.Idle
        trailingEvaluationGeneration = null
    }

    fun onAuthoritativeInactiveTransition() {
        state = State.Idle
        trailingEvaluationGeneration = null
    }

    private sealed interface State {
        val generation: Long?

        data object Idle : State {
            override val generation: Long? = null
        }

        data class Armed(
            override val generation: Long,
            val firstEventElapsedMillis: Long,
            val deadlineElapsedMillis: Long,
        ) : State

        data class Consumed(
            override val generation: Long,
        ) : State
    }

    companion object {
        const val WATCHDOG_DELAY_MILLIS = 1_000L
    }
}
