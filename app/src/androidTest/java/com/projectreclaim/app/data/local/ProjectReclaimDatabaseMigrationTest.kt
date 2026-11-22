package com.projectreclaim.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectReclaimDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ProjectReclaimDatabase::class.java,
    )

    @Test
    fun migrate1To2AddsNullableConfirmationAndPreservesLegacyRows() {
        helper.createDatabase(V1_TO_V2_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO usage_sessions (
                    id,
                    platform,
                    usageType,
                    startTimeMillis,
                    endTimeMillis,
                    detectorVersion
                ) VALUES (1, 'instagram', 'reels', 100, NULL, 'legacy')
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            V1_TO_V2_DATABASE,
            2,
            true,
            ProjectReclaimDatabase.MIGRATION_1_2,
        ).use { database ->
            database.query(
                """
                SELECT startTimeMillis, endTimeMillis, lastConfirmedActiveTimeMillis
                FROM usage_sessions
                WHERE id = 1
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(100L, cursor.getLong(0))
                assertNull(if (cursor.isNull(1)) null else cursor.getLong(1))
                assertNull(if (cursor.isNull(2)) null else cursor.getLong(2))
            }
        }
    }

    @Test
    fun migrate2To3ConservativelyClosesEveryInheritedOpenRow() {
        helper.createDatabase(V2_TO_V3_DATABASE, 2).apply {
            insertSession(1, "instagram", "reels", 100, lastConfirmed = 150)
            insertSession(2, "instagram", "reels", 200, lastConfirmed = null)
            insertSession(3, "instagram", "reels", 300, lastConfirmed = 250)
            insertSession(4, "youtube", "shorts", 400, lastConfirmed = 450)
            insertSession(5, "instagram", "reels", 500, end = 600, lastConfirmed = 550)
            close()
        }

        helper.runMigrationsAndValidate(
            V2_TO_V3_DATABASE,
            3,
            true,
            ProjectReclaimDatabase.MIGRATION_2_3,
        ).use { database ->
            database.query("SELECT id, endTimeMillis FROM usage_sessions ORDER BY id").use { cursor ->
                val migratedEnds = buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getLong(0) to cursor.getLong(1))
                    }
                }
                assertEquals(
                    listOf(
                        1L to 150L,
                        2L to 200L,
                        3L to 300L,
                        4L to 450L,
                        5L to 600L,
                    ),
                    migratedEnds,
                )
            }
            assertEquals(
                0L,
                database.longQuery(
                    "SELECT COUNT(*) FROM usage_sessions WHERE endTimeMillis IS NULL",
                ),
            )
        }
    }

    @Test
    fun migrate1To3RunsFullChainAndInstallsInvariant() {
        helper.createDatabase(V1_TO_V3_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO usage_sessions (
                    id,
                    platform,
                    usageType,
                    startTimeMillis,
                    endTimeMillis,
                    detectorVersion
                ) VALUES (1, 'instagram', 'reels', 100, NULL, 'legacy')
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            V1_TO_V3_DATABASE,
            3,
            true,
            ProjectReclaimDatabase.MIGRATION_1_2,
            ProjectReclaimDatabase.MIGRATION_2_3,
        ).use { database ->
            assertEquals(
                100L,
                database.longQuery("SELECT endTimeMillis FROM usage_sessions WHERE id = 1"),
            )
            database.insertSession(2, "instagram", "reels", 200, lastConfirmed = 200)
            val duplicateInsert = runCatching {
                database.insertSession(3, "instagram", "reels", 300, lastConfirmed = 300)
            }
            assertTrue(duplicateInsert.isFailure)
        }
    }

    @Test
    fun migratedDatabaseEnforcesInsertUpdateAndHeartbeatBehavior() {
        helper.createDatabase(INVARIANT_DATABASE, 2).close()

        helper.runMigrationsAndValidate(
            INVARIANT_DATABASE,
            3,
            true,
            ProjectReclaimDatabase.MIGRATION_2_3,
        ).use { database ->
            database.insertSession(1, "instagram", "reels", 100, lastConfirmed = 100)

            val duplicateInsert = runCatching {
                database.insertSession(2, "instagram", "reels", 110, lastConfirmed = 110)
            }
            assertTrue(duplicateInsert.isFailure)

            database.insertSession(3, "youtube", "shorts", 120, lastConfirmed = 120)
            database.insertSession(4, "instagram", "stories", 130, lastConfirmed = 130)
            database.insertSession(
                5,
                "instagram",
                "reels",
                50,
                end = 90,
                lastConfirmed = 80,
            )

            database.execSQL(
                "UPDATE usage_sessions SET lastConfirmedActiveTimeMillis = 175 WHERE id = 1",
            )
            assertEquals(
                175L,
                database.longQuery(
                    "SELECT lastConfirmedActiveTimeMillis FROM usage_sessions WHERE id = 1",
                ),
            )

            val reopenIntoOccupiedScope = runCatching {
                database.execSQL("UPDATE usage_sessions SET endTimeMillis = NULL WHERE id = 5")
            }
            assertTrue(reopenIntoOccupiedScope.isFailure)
            assertEquals(
                90L,
                database.longQuery("SELECT endTimeMillis FROM usage_sessions WHERE id = 5"),
            )

            val moveIntoOccupiedScope = runCatching {
                database.execSQL(
                    """
                    UPDATE usage_sessions
                    SET platform = 'instagram', usageType = 'stories'
                    WHERE id = 3
                    """.trimIndent(),
                )
            }
            assertTrue(moveIntoOccupiedScope.isFailure)
            assertEquals(
                "youtube/shorts",
                database.stringQuery(
                    "SELECT platform || '/' || usageType FROM usage_sessions WHERE id = 3",
                ),
            )

            database.execSQL("UPDATE usage_sessions SET endTimeMillis = 200 WHERE id = 1")
            database.insertSession(6, "instagram", "reels", 210, lastConfirmed = 210)

            assertEquals(
                1L,
                database.longQuery(
                    """
                    SELECT COUNT(*) FROM usage_sessions
                    WHERE platform = 'instagram'
                        AND usageType = 'reels'
                        AND endTimeMillis IS NULL
                    """.trimIndent(),
                ),
            )
            assertEquals(
                1L,
                database.longQuery(
                    """
                    SELECT COUNT(*) FROM usage_sessions
                    WHERE platform = 'youtube'
                        AND usageType = 'shorts'
                        AND endTimeMillis IS NULL
                    """.trimIndent(),
                ),
            )
            assertEquals(
                1L,
                database.longQuery(
                    """
                    SELECT COUNT(*) FROM usage_sessions
                    WHERE platform = 'instagram'
                        AND usageType = 'stories'
                        AND endTimeMillis IS NULL
                    """.trimIndent(),
                ),
            )
        }
    }

    private fun SupportSQLiteDatabase.insertSession(
        id: Long,
        platform: String,
        usageType: String,
        start: Long,
        end: Long? = null,
        lastConfirmed: Long?,
    ) {
        execSQL(
            """
            INSERT INTO usage_sessions (
                id,
                platform,
                usageType,
                startTimeMillis,
                endTimeMillis,
                detectorVersion,
                lastConfirmedActiveTimeMillis
            ) VALUES (?, ?, ?, ?, ?, 'test', ?)
            """.trimIndent(),
            arrayOf<Any?>(id, platform, usageType, start, end, lastConfirmed),
        )
    }

    private fun SupportSQLiteDatabase.longQuery(sql: String): Long = query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun SupportSQLiteDatabase.stringQuery(sql: String): String = query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

    private companion object {
        const val V1_TO_V2_DATABASE = "a9b-v1-to-v2-migration-test"
        const val V2_TO_V3_DATABASE = "a9b-v2-to-v3-migration-test"
        const val V1_TO_V3_DATABASE = "a9b-v1-to-v3-migration-test"
        const val INVARIANT_DATABASE = "a9b-invariant-migration-test"
    }
}
