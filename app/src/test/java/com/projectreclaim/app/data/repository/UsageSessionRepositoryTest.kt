package com.projectreclaim.app.data.repository

import com.projectreclaim.app.data.local.UsageSessionDao
import com.projectreclaim.app.data.local.UsageSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageSessionRepositoryTest {
    @Test
    fun `new session persists start as initial active confirmation`() = runBlocking {
        val dao = RecordingUsageSessionDao()
        val repository = UsageSessionRepository(dao)

        val sessionId = repository.startSession(
            platform = "instagram",
            usageType = "reels",
            startTimeMillis = 123,
            detectorVersion = "test-detector",
        )

        assertEquals(7L, sessionId)
        assertEquals(123L, dao.insertedSession?.startTimeMillis)
        assertEquals(123L, dao.insertedSession?.lastConfirmedActiveTimeMillis)
    }

    private class RecordingUsageSessionDao : UsageSessionDao {
        var insertedSession: UsageSessionEntity? = null

        override suspend fun insert(session: UsageSessionEntity): Long {
            insertedSession = session
            return 7L
        }

        override suspend fun finishSession(sessionId: Long, endTimeMillis: Long): Int =
            error("Not used")

        override suspend fun advanceLastConfirmedActiveTime(
            sessionId: Long,
            timestampMillis: Long,
        ): Int = error("Not used")

        override suspend fun getSessionsOverlapping(
            platform: String,
            usageType: String,
            rangeStartMillis: Long,
            rangeEndMillis: Long,
        ): List<UsageSessionEntity> = error("Not used")

        override suspend fun getOpenSessions(
            platform: String,
            usageType: String,
        ): List<UsageSessionEntity> = error("Not used")
    }
}
