package com.projectreclaim.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageSessionDaoTest {
    private lateinit var context: Context
    private lateinit var database: ProjectReclaimDatabase
    private lateinit var dao: UsageSessionDao

    @Before
    fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DATABASE)
        database = ProjectReclaimDatabase.createBuilder(context, TEST_DATABASE)
            .allowMainThreadQueries()
            .build()
        dao = database.usageSessionDao()
    }

    @After
    fun closeDatabase() {
        database.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun insertedSessionCanBeReadBack() = runBlocking {
        val sessionId = dao.insert(session(start = 100, end = 200))

        val sessions = dao.getSessionsOverlapping(
            platform = "instagram",
            usageType = "reels",
            rangeStartMillis = 0,
            rangeEndMillis = 300,
        )

        assertEquals(1, sessions.size)
        assertEquals(sessionId, sessions.single().id)
        assertEquals("instagram", sessions.single().platform)
    }

    @Test
    fun openSessionCanBeFinished() = runBlocking {
        val sessionId = dao.insert(session(start = 100, end = null))
        assertNotNull(openSession(platform = "instagram", usageType = "reels"))

        assertEquals(1, dao.finishSession(sessionId, endTimeMillis = 250))

        assertNull(openSession(platform = "instagram", usageType = "reels"))
        assertEquals(
            250L,
            dao.getSessionsOverlapping("instagram", "reels", 0, 300).single().endTimeMillis,
        )
    }

    @Test
    fun finishedSessionCannotBeFinishedAgain() = runBlocking {
        val sessionId = dao.insert(session(start = 100, end = null))

        assertEquals(1, dao.finishSession(sessionId, endTimeMillis = 250))
        assertEquals(0, dao.finishSession(sessionId, endTimeMillis = 300))

        assertEquals(
            250L,
            dao.getSessionsOverlapping("instagram", "reels", 0, 400).single().endTimeMillis,
        )
    }

    @Test
    fun lastConfirmedTimestampOnlyAdvancesForOpenSession() = runBlocking {
        val sessionId = dao.insert(session(start = 100, end = null, lastConfirmed = 100))

        assertEquals(1, dao.advanceLastConfirmedActiveTime(sessionId, 200))
        assertEquals(0, dao.advanceLastConfirmedActiveTime(sessionId, 150))
        assertEquals(
            200L,
            openSession("instagram", "reels")?.lastConfirmedActiveTimeMillis,
        )

        assertEquals(1, dao.finishSession(sessionId, 250))
        assertEquals(0, dao.advanceLastConfirmedActiveTime(sessionId, 300))
        assertEquals(
            200L,
            dao.getSessionsOverlapping("instagram", "reels", 0, 400)
                .single()
                .lastConfirmedActiveTimeMillis,
        )
    }

    @Test
    fun allOpenSessionsLookupReturnsTheScopedRow() = runBlocking {
        val firstId = dao.insert(session(start = 100, end = null, lastConfirmed = 110))
        dao.insert(session(start = 200, end = 250, lastConfirmed = 220))
        dao.insert(session(start = 300, end = null, platform = "youtube", usageType = "shorts"))

        val openSessions = dao.getOpenSessions("instagram", "reels")

        assertEquals(listOf(firstId), openSessions.map { it.id })
    }

    @Test
    fun rangeQueryUsesHalfOpenIntervalBoundaries() = runBlocking {
        val crossingStartId = dao.insert(session(start = 50, end = 150))
        val crossingEndId = dao.insert(session(start = 150, end = 250))
        dao.insert(session(start = 0, end = 100))
        dao.insert(session(start = 200, end = 250))

        val sessions = dao.getSessionsOverlapping(
            platform = "instagram",
            usageType = "reels",
            rangeStartMillis = 100,
            rangeEndMillis = 200,
        )

        assertEquals(listOf(crossingStartId, crossingEndId), sessions.map { it.id })
    }

    @Test
    fun openSessionLookupIsScopedByPlatformAndUsageType() = runBlocking {
        val reelsId = dao.insert(session(start = 150, end = null))
        val storiesId = dao.insert(session(start = 200, end = null, usageType = "stories"))
        val tiktokId = dao.insert(
            session(start = 300, end = null, platform = "tiktok", usageType = "reels"),
        )

        assertEquals(
            reelsId,
            openSession(platform = "instagram", usageType = "reels")?.id,
        )
        assertEquals(
            storiesId,
            openSession(platform = "instagram", usageType = "stories")?.id,
        )
        assertEquals(
            tiktokId,
            openSession(platform = "tiktok", usageType = "reels")?.id,
        )
    }

    @Test
    fun freshDatabaseEnforcesOneOpenSessionPerScope() = runBlocking {
        val firstId = dao.insert(session(start = 100, end = null))

        val duplicateInsert = runCatching {
            dao.insert(session(start = 150, end = null))
        }

        assertTrue(duplicateInsert.isFailure)
        assertEquals(listOf(firstId), dao.getOpenSessions("instagram", "reels").map { it.id })

        val youtubeId = dao.insert(
            session(start = 200, end = null, platform = "youtube", usageType = "shorts"),
        )
        val storiesId = dao.insert(session(start = 250, end = null, usageType = "stories"))
        assertNotNull(openSession("youtube", "shorts"))
        assertNotNull(openSession("instagram", "stories"))

        assertEquals(1, dao.finishSession(firstId, endTimeMillis = 300))
        val replacementId = dao.insert(session(start = 350, end = null))

        assertEquals(replacementId, openSession("instagram", "reels")?.id)
        assertEquals(youtubeId, openSession("youtube", "shorts")?.id)
        assertEquals(storiesId, openSession("instagram", "stories")?.id)
    }

    @Test
    fun autoGeneratedInsertCannotBypassOpenSessionInvariantWhenExistingIdIsNegativeOne() =
        runBlocking {
            val explicitId = dao.insert(session(id = -1, start = 100, end = null))
            assertEquals(-1L, explicitId)

            val duplicateInsert = runCatching {
                dao.insert(session(start = 150, end = null))
            }

            assertTrue(duplicateInsert.isFailure)
            assertEquals(
                listOf(-1L),
                dao.getOpenSessions("instagram", "reels").map { it.id },
            )
        }

    @Test
    fun overlapQueryIsScopedByPlatformAndUsageType() = runBlocking {
        val matchingClosedId = dao.insert(session(start = 100, end = 200))
        val matchingOpenId = dao.insert(session(start = 250, end = null))
        dao.insert(session(start = 110, end = 190, usageType = "stories"))
        dao.insert(session(start = 120, end = 180, platform = "tiktok"))
        dao.insert(session(start = 0, end = 100))
        dao.insert(session(start = 300, end = 400))

        val sessions = dao.getSessionsOverlapping(
            platform = "instagram",
            usageType = "reels",
            rangeStartMillis = 100,
            rangeEndMillis = 300,
        )

        assertEquals(listOf(matchingClosedId, matchingOpenId), sessions.map { it.id })
    }

    private fun session(
        id: Long = 0,
        start: Long,
        end: Long?,
        platform: String = "instagram",
        usageType: String = "reels",
        lastConfirmed: Long? = null,
    ) = UsageSessionEntity(
        id = id,
        platform = platform,
        usageType = usageType,
        startTimeMillis = start,
        endTimeMillis = end,
        detectorVersion = "test",
        lastConfirmedActiveTimeMillis = lastConfirmed,
    )

    private suspend fun openSession(
        platform: String,
        usageType: String,
    ): UsageSessionEntity? = dao.getOpenSessions(platform, usageType).singleOrNull()

    private companion object {
        const val TEST_DATABASE = "a9b-usage-session-dao-test"
    }
}
