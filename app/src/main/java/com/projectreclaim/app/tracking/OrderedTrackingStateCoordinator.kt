package com.projectreclaim.app.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val ACTIVE_SESSION_HEARTBEAT_INTERVAL_MILLIS = 5_000L

/**
 * Suppresses repeated inactive states and reconciles observations in observation order. Repeated
 * active observations remain significant because they confirm that the current session is alive.
 * This owns no persistence state; [reconcile] remains the durable source-of-truth operation.
 */
class OrderedTrackingStateCoordinator(
    private val scope: CoroutineScope,
    private val reconcile: suspend (isActive: Boolean, timestampMillis: Long) -> Unit,
    startupAction: (suspend () -> Unit)? = null,
    private val onReconciled: (isActive: Boolean) -> Unit = {},
    private val onFailure: (isActive: Boolean, error: Throwable) -> Unit = { _, _ -> },
    private val onStartupFailure: (error: Throwable) -> Unit = {},
    private val heartbeatIntervalMillis: Long? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    heartbeatScheduler: ((Long, () -> Unit) -> (() -> Unit))? = null,
) {
    private val scheduleHeartbeat = heartbeatScheduler ?: fun(
        delayMillis: Long,
        action: () -> Unit,
    ): () -> Unit {
        val job = scope.launch {
            delay(delayMillis)
            action()
        }
        return { job.cancel() }
    }
    private val stateLock = Any()
    private var lastAcceptedState: Boolean? = null
    private var lastAcceptedSequence = 0L
    private var lastAcceptedTimestampMillis: Long? = null
    private var activeGeneration = 0L
    private var scheduledHeartbeatGeneration: Long? = null
    private var cancelScheduledHeartbeat: (() -> Unit)? = null
    private var tailJob: Job? = startupAction?.let { action ->
        scope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onStartupFailure(error)
            }
        }
    }
    private var isShutDown = false

    init {
        require(heartbeatIntervalMillis == null || heartbeatIntervalMillis > 0L) {
            "Heartbeat interval must be positive when enabled"
        }
    }

    fun observe(isActive: Boolean, timestampMillis: Long): Job? = synchronized(stateLock) {
        if (isShutDown || (!isActive && lastAcceptedState == false)) return@synchronized null

        invalidateScheduledHeartbeatLocked()
        activeGeneration += 1L
        lastAcceptedState = isActive
        lastAcceptedSequence += 1L
        enqueueLocked(
            isActive = isActive,
            timestampMillis = orderedTimestampLocked(timestampMillis),
            sequence = lastAcceptedSequence,
            generation = activeGeneration,
        )
    }

    /** Starts lazy coordinators and returns the ordered startup work, if configured. */
    fun start(): Job? = synchronized(stateLock) { tailJob }

    /** Stops accepting observations and orders a final inactive state after prior work. */
    fun shutdown(timestampMillis: Long): Job? = synchronized(stateLock) {
        if (!isShutDown) {
            isShutDown = true
            invalidateScheduledHeartbeatLocked()
            activeGeneration += 1L
            if (lastAcceptedState != false) {
                lastAcceptedState = false
                lastAcceptedSequence += 1L
                enqueueLocked(
                    isActive = false,
                    timestampMillis = orderedTimestampLocked(timestampMillis),
                    sequence = lastAcceptedSequence,
                    generation = activeGeneration,
                )
            }
        }
        tailJob
    }

    suspend fun awaitIdle() {
        val job = synchronized(stateLock) { tailJob }
        job?.join()
    }

    private fun enqueueLocked(
        isActive: Boolean,
        timestampMillis: Long,
        sequence: Long,
        generation: Long,
    ): Job {
        val predecessor = tailJob
        return scope.launch {
            predecessor?.join()
            try {
                reconcile(isActive, timestampMillis)
                onReconciled(isActive)
                if (isActive) {
                    armHeartbeatAfterSuccessfulActiveReconciliation(generation)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                synchronized(stateLock) {
                    if (lastAcceptedSequence == sequence) {
                        lastAcceptedState = null
                        invalidateScheduledHeartbeatLocked()
                        activeGeneration += 1L
                    }
                }
                onFailure(isActive, error)
            }
        }.also { tailJob = it }
    }

    private fun armHeartbeatAfterSuccessfulActiveReconciliation(generation: Long) {
        synchronized(stateLock) {
            val intervalMillis = heartbeatIntervalMillis ?: return
            if (
                isShutDown ||
                lastAcceptedState != true ||
                activeGeneration != generation
            ) {
                return
            }

            invalidateScheduledHeartbeatLocked()
            scheduledHeartbeatGeneration = generation
            cancelScheduledHeartbeat = scheduleHeartbeat(intervalMillis) {
                enqueueHeartbeatIfCurrent(generation)
            }
        }
    }

    private fun enqueueHeartbeatIfCurrent(generation: Long) {
        synchronized(stateLock) {
            if (scheduledHeartbeatGeneration != generation) return

            scheduledHeartbeatGeneration = null
            cancelScheduledHeartbeat = null
            if (
                isShutDown ||
                lastAcceptedState != true ||
                activeGeneration != generation
            ) {
                return
            }

            lastAcceptedSequence += 1L
            enqueueLocked(
                isActive = true,
                timestampMillis = orderedTimestampLocked(nowMillis()),
                sequence = lastAcceptedSequence,
                generation = generation,
            )
        }
    }

    private fun invalidateScheduledHeartbeatLocked() {
        scheduledHeartbeatGeneration = null
        cancelScheduledHeartbeat?.invoke()
        cancelScheduledHeartbeat = null
    }

    private fun orderedTimestampLocked(timestampMillis: Long): Long {
        val orderedTimestamp = maxOf(timestampMillis, lastAcceptedTimestampMillis ?: timestampMillis)
        lastAcceptedTimestampMillis = orderedTimestamp
        return orderedTimestamp
    }
}
