package com.projectreclaim.app.detection

enum class InstagramReelsEvaluationOrigin {
    SERVICE_CONNECTED,
    WINDOW_STATE_CHANGED,
    WINDOWS_CHANGED,
    CONTENT_CHANGED,
    CONTENT_STARVATION_WATCHDOG,
    OTHER,
}

enum class InstagramReelsRootEvidence {
    INSTAGRAM,
    UNAVAILABLE,
    KNOWN_NON_INSTAGRAM,
    UNKNOWN_PACKAGE,
}

data class InstagramReelsEvaluationResult(
    val rootEvidence: InstagramReelsRootEvidence,
    val detectorResult: Boolean,
)

data class InstagramReelsRetrySchedule(
    val generation: Long,
    val deadlineElapsedMillis: Long,
)

/**
 * Owns the bounded Instagram root-readiness retry burst without depending on Android timing APIs.
 * The service remains responsible for root access, detection, publication, and Handler scheduling.
 */
class InstagramReelsRetryController {
    private val retryOffsetsMillis = RETRY_OFFSETS_MILLIS
    private var generation = 0L
    private var burst: Burst? = null

    init {
        require(retryOffsetsMillis.isNotEmpty())
        require(retryOffsetsMillis.all { it > 0L })
        require(retryOffsetsMillis.zipWithNext().all { (earlier, later) -> earlier < later })
    }

    fun onNormalEvaluation(
        origin: InstagramReelsEvaluationOrigin,
        hasInstagramPackageHint: Boolean,
        result: InstagramReelsEvaluationResult,
        nowElapsedMillis: Long,
    ): InstagramReelsRetrySchedule? {
        if (origin == InstagramReelsEvaluationOrigin.CONTENT_STARVATION_WATCHDOG) return null
        if (result.detectorResult ||
            result.rootEvidence == InstagramReelsRootEvidence.KNOWN_NON_INSTAGRAM
        ) {
            cancel()
            return null
        }

        if (!isEligibleFailure(origin, hasInstagramPackageHint, result.rootEvidence)) {
            return null
        }

        return startBurst(nowElapsedMillis)
    }

    fun onContentStarvationWatchdogEvaluation(
        hasInstagramPackageHint: Boolean,
        result: InstagramReelsEvaluationResult,
        nowElapsedMillis: Long,
    ): InstagramReelsRetrySchedule? {
        if (result.detectorResult ||
            result.rootEvidence == InstagramReelsRootEvidence.KNOWN_NON_INSTAGRAM
        ) {
            cancel()
            return null
        }

        if (burst != null) return null
        if (!isEligibleFailure(
                origin = InstagramReelsEvaluationOrigin.CONTENT_STARVATION_WATCHDOG,
                hasInstagramPackageHint = hasInstagramPackageHint,
                rootEvidence = result.rootEvidence,
            )
        ) {
            return null
        }

        return startBurst(nowElapsedMillis)
    }

    private fun startBurst(nowElapsedMillis: Long): InstagramReelsRetrySchedule? {
        generation += 1L
        burst = Burst(
            generation = generation,
            startElapsedMillis = nowElapsedMillis,
            nextRetryIndex = 0,
            retryEvaluations = 0,
            retryInFlight = false,
        )
        return currentSchedule()
    }

    fun beginRetry(
        retryGeneration: Long,
        deadlineElapsedMillis: Long,
        nowElapsedMillis: Long,
    ): Boolean {
        val current = burst ?: return false
        if (current.generation != retryGeneration || current.retryInFlight) return false
        if (current.retryEvaluations >= retryOffsetsMillis.size) return false
        if (deadlineFor(current, current.nextRetryIndex) != deadlineElapsedMillis) return false
        if (nowElapsedMillis < deadlineElapsedMillis) return false
        val finalDeadlineElapsedMillis =
            deadlineFor(current, retryOffsetsMillis.lastIndex)
        if (deadlineElapsedMillis < finalDeadlineElapsedMillis &&
            nowElapsedMillis >= finalDeadlineElapsedMillis
        ) {
            cancel()
            return false
        }

        burst = current.copy(
            nextRetryIndex = current.nextRetryIndex + 1,
            retryEvaluations = current.retryEvaluations + 1,
            retryInFlight = true,
        )
        return true
    }

    fun onRetryEvaluation(
        retryGeneration: Long,
        result: InstagramReelsEvaluationResult,
        nowElapsedMillis: Long,
    ): InstagramReelsRetrySchedule? {
        val current = burst ?: return null
        if (current.generation != retryGeneration || !current.retryInFlight) return null

        if (result.detectorResult ||
            result.rootEvidence == InstagramReelsRootEvidence.KNOWN_NON_INSTAGRAM
        ) {
            cancel()
            return null
        }

        var nextIndex = current.nextRetryIndex
        while (nextIndex < retryOffsetsMillis.size &&
            deadlineFor(current, nextIndex) <= nowElapsedMillis
        ) {
            nextIndex += 1
        }

        if (nextIndex >= retryOffsetsMillis.size) {
            cancel()
            return null
        }

        burst = current.copy(
            nextRetryIndex = nextIndex,
            retryInFlight = false,
        )
        return currentSchedule()
    }

    fun abandon(retryGeneration: Long) {
        if (burst?.generation == retryGeneration) cancel()
    }

    fun cancel() {
        generation += 1L
        burst = null
    }

    private fun currentSchedule(): InstagramReelsRetrySchedule? {
        val current = burst ?: return null
        if (current.nextRetryIndex >= retryOffsetsMillis.size) return null
        return InstagramReelsRetrySchedule(
            generation = current.generation,
            deadlineElapsedMillis = deadlineFor(current, current.nextRetryIndex),
        )
    }

    private fun deadlineFor(current: Burst, retryIndex: Int): Long =
        current.startElapsedMillis + retryOffsetsMillis[retryIndex]

    private fun isEligibleFailure(
        origin: InstagramReelsEvaluationOrigin,
        hasInstagramPackageHint: Boolean,
        rootEvidence: InstagramReelsRootEvidence,
    ): Boolean = when (rootEvidence) {
        InstagramReelsRootEvidence.INSTAGRAM ->
            origin == InstagramReelsEvaluationOrigin.SERVICE_CONNECTED ||
                origin == InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED ||
                origin == InstagramReelsEvaluationOrigin.WINDOWS_CHANGED ||
                origin == InstagramReelsEvaluationOrigin.CONTENT_STARVATION_WATCHDOG

        InstagramReelsRootEvidence.UNAVAILABLE ->
            origin == InstagramReelsEvaluationOrigin.SERVICE_CONNECTED ||
                hasInstagramPackageHint &&
                (origin == InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED ||
                    origin == InstagramReelsEvaluationOrigin.WINDOWS_CHANGED ||
                    origin == InstagramReelsEvaluationOrigin.CONTENT_STARVATION_WATCHDOG)

        InstagramReelsRootEvidence.UNKNOWN_PACKAGE ->
            hasInstagramPackageHint &&
                (origin == InstagramReelsEvaluationOrigin.WINDOW_STATE_CHANGED ||
                    origin == InstagramReelsEvaluationOrigin.WINDOWS_CHANGED ||
                    origin == InstagramReelsEvaluationOrigin.CONTENT_STARVATION_WATCHDOG)

        InstagramReelsRootEvidence.KNOWN_NON_INSTAGRAM -> false
    }

    private data class Burst(
        val generation: Long,
        val startElapsedMillis: Long,
        val nextRetryIndex: Int,
        val retryEvaluations: Int,
        val retryInFlight: Boolean,
    )

    companion object {
        private val RETRY_OFFSETS_MILLIS = listOf(400L, 1_400L, 3_400L, 7_900L)
    }
}
