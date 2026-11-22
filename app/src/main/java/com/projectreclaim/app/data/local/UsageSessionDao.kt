package com.projectreclaim.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UsageSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: UsageSessionEntity): Long

    @Query(
        """
        UPDATE usage_sessions
        SET endTimeMillis = :endTimeMillis
        WHERE id = :sessionId AND endTimeMillis IS NULL
        """,
    )
    suspend fun finishSession(sessionId: Long, endTimeMillis: Long): Int

    @Query(
        """
        UPDATE usage_sessions
        SET lastConfirmedActiveTimeMillis = :timestampMillis
        WHERE id = :sessionId
          AND endTimeMillis IS NULL
          AND (
            lastConfirmedActiveTimeMillis IS NULL
            OR lastConfirmedActiveTimeMillis < :timestampMillis
          )
        """,
    )
    suspend fun advanceLastConfirmedActiveTime(
        sessionId: Long,
        timestampMillis: Long,
    ): Int

    @Query(
        """
        SELECT * FROM usage_sessions
        WHERE platform = :platform
          AND usageType = :usageType
          AND startTimeMillis < :rangeEndMillis
          AND (endTimeMillis IS NULL OR endTimeMillis > :rangeStartMillis)
        ORDER BY startTimeMillis ASC
        """,
    )
    suspend fun getSessionsOverlapping(
        platform: String,
        usageType: String,
        rangeStartMillis: Long,
        rangeEndMillis: Long,
    ): List<UsageSessionEntity>

    @Query(
        """
        SELECT * FROM usage_sessions
        WHERE platform = :platform
          AND usageType = :usageType
          AND endTimeMillis IS NULL
        ORDER BY startTimeMillis ASC, id ASC
        """,
    )
    suspend fun getOpenSessions(
        platform: String,
        usageType: String,
    ): List<UsageSessionEntity>
}
