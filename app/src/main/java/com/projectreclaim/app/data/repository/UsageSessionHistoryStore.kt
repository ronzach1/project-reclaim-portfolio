package com.projectreclaim.app.data.repository

import com.projectreclaim.app.data.local.UsageSessionEntity

/** Read boundary for querying persisted session history without exposing Room to use cases. */
interface UsageSessionHistoryStore {
    suspend fun getSessionsOverlapping(
        platform: String,
        usageType: String,
        rangeStartMillis: Long,
        rangeEndMillis: Long,
    ): List<UsageSessionEntity>
}
