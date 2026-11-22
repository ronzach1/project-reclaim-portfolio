package com.projectreclaim.app.tracking

import com.projectreclaim.app.data.repository.UsageSessionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val A3_INSTAGRAM_REELS_DETECTOR_VERSION = "a3-instagram-reels-v1"
const val A7B_YOUTUBE_SHORTS_DETECTOR_VERSION = "a7b-youtube-shorts-v1"

/** Reconciles one detector's desired state with durable sessions for one platform/type pair. */
class UsageSessionTracker(
    private val store: UsageSessionStore,
    private val platform: String,
    private val usageType: String,
    private val detectorVersion: String,
) {
    private val reconcileMutex = Mutex()
    private var staleSessionsRecovered = false

    /** Closes every open row inherited from an earlier writer at its last authoritative evidence. */
    suspend fun recoverStaleOpenSessions() {
        reconcileMutex.withLock {
            recoverStaleOpenSessionsLocked()
        }
    }

    suspend fun reconcile(isActive: Boolean, timestampMillis: Long) {
        reconcileMutex.withLock {
            // This retry also makes a failed startup recovery fail closed: an old row is never
            // adopted by normal reconciliation before recovery succeeds.
            recoverStaleOpenSessionsLocked()
            val openSessions = store.getOpenSessions(platform, usageType)
            check(openSessions.size <= 1) {
                "Open-session invariant violated for $platform/$usageType: " +
                    "found ${openSessions.size} open rows"
            }
            val openSession = openSessions.singleOrNull()

            if (isActive) {
                if (openSession == null) {
                    store.startSession(
                        platform = platform,
                        usageType = usageType,
                        startTimeMillis = timestampMillis,
                        detectorVersion = detectorVersion,
                    )
                } else {
                    store.advanceLastConfirmedActiveTime(
                        sessionId = openSession.id,
                        timestampMillis = timestampMillis,
                    )
                }
            } else if (openSession != null) {
                val finished = try {
                    store.finishSession(
                        sessionId = openSession.id,
                        endTimeMillis = maxOf(timestampMillis, openSession.startTimeMillis),
                    )
                } catch (error: Throwable) {
                    staleSessionsRecovered = false
                    throw error
                }
                if (!finished) {
                    val targetStillOpen = try {
                        store.getOpenSessions(platform, usageType).any {
                            it.id == openSession.id
                        }
                    } catch (error: Throwable) {
                        staleSessionsRecovered = false
                        throw error
                    }
                    if (targetStillOpen) {
                        staleSessionsRecovered = false
                        error(
                            "Failed to finish open session ${openSession.id} " +
                                "for $platform/$usageType",
                        )
                    }
                }
            }
        }
    }

    private suspend fun recoverStaleOpenSessionsLocked() {
        if (staleSessionsRecovered) return

        store.getOpenSessions(platform, usageType).forEach { staleSession ->
            val recoveryEndMillis = maxOf(
                staleSession.startTimeMillis,
                staleSession.lastConfirmedActiveTimeMillis ?: staleSession.startTimeMillis,
            )
            store.finishSession(staleSession.id, recoveryEndMillis)
        }

        check(store.getOpenSessions(platform, usageType).isEmpty()) {
            "Failed to recover all stale open sessions for $platform/$usageType"
        }
        staleSessionsRecovered = true
    }
}
