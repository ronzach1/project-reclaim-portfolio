package com.projectreclaim.app.data.repository

import com.projectreclaim.app.data.local.UsageSessionDao
import com.projectreclaim.app.data.local.UsageSessionEntity

/** Small boundary for future tracking code; UI and detectors do not need DAO access. */
class UsageSessionRepository(private val usageSessionDao: UsageSessionDao) :
    UsageSessionStore,
    UsageSessionHistoryStore {
    suspend fun startSession(
        platform: String,
        usageType: String,
        startTimeMillis: Long,
    ): Long = startSession(
        platform = platform,
        usageType = usageType,
        startTimeMillis = startTimeMillis,
        detectorVersion = null,
    )

    override suspend fun startSession(
        platform: String,
        usageType: String,
        startTimeMillis: Long,
        detectorVersion: String?,
    ): Long = usageSessionDao.insert(
        UsageSessionEntity(
            platform = platform,
            usageType = usageType,
            startTimeMillis = startTimeMillis,
            detectorVersion = detectorVersion,
            lastConfirmedActiveTimeMillis = startTimeMillis,
        ),
    )

    override suspend fun finishSession(sessionId: Long, endTimeMillis: Long): Boolean =
        usageSessionDao.finishSession(sessionId, endTimeMillis) == 1

    override suspend fun advanceLastConfirmedActiveTime(
        sessionId: Long,
        timestampMillis: Long,
    ): Boolean = usageSessionDao.advanceLastConfirmedActiveTime(sessionId, timestampMillis) == 1

    override suspend fun getSessionsOverlapping(
        platform: String,
        usageType: String,
        rangeStartMillis: Long,
        rangeEndMillis: Long,
    ): List<UsageSessionEntity> = usageSessionDao.getSessionsOverlapping(
        platform = platform,
        usageType = usageType,
        rangeStartMillis = rangeStartMillis,
        rangeEndMillis = rangeEndMillis,
    )

    override suspend fun getOpenSessions(
        platform: String,
        usageType: String,
    ): List<UsageSessionEntity> = usageSessionDao.getOpenSessions(platform, usageType)
}
