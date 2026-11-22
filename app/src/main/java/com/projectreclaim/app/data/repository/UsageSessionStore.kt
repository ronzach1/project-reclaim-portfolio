package com.projectreclaim.app.data.repository

import com.projectreclaim.app.data.local.UsageSessionEntity

/** Minimal persistence boundary used by session trackers; it deliberately does not expose Room. */
interface UsageSessionStore {
    suspend fun startSession(
        platform: String,
        usageType: String,
        startTimeMillis: Long,
        detectorVersion: String?,
    ): Long

    suspend fun finishSession(sessionId: Long, endTimeMillis: Long): Boolean

    suspend fun advanceLastConfirmedActiveTime(
        sessionId: Long,
        timestampMillis: Long,
    ): Boolean

    suspend fun getOpenSessions(
        platform: String,
        usageType: String,
    ): List<UsageSessionEntity>
}
