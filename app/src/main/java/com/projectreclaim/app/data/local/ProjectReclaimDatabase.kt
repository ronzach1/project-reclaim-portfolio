package com.projectreclaim.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [UsageSessionEntity::class], version = 3, exportSchema = true)
abstract class ProjectReclaimDatabase : RoomDatabase() {
    abstract fun usageSessionDao(): UsageSessionDao

    companion object {
        private const val DATABASE_NAME = "project-reclaim.db"
        private const val OPEN_SESSION_INVARIANT_MESSAGE =
            "Only one open usage session is allowed per platform and usage type"

        @Volatile
        private var instance: ProjectReclaimDatabase? = null

        fun getInstance(context: Context): ProjectReclaimDatabase =
            instance ?: synchronized(this) {
                instance ?: createBuilder(context, DATABASE_NAME)
                    .build()
                    .also { instance = it }
            }

        internal fun createBuilder(
            context: Context,
            databaseName: String,
        ): RoomDatabase.Builder<ProjectReclaimDatabase> = Room.databaseBuilder(
            context.applicationContext,
            ProjectReclaimDatabase::class.java,
            databaseName,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .addCallback(OPEN_SESSION_INTEGRITY_CALLBACK)

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE usage_sessions " +
                        "ADD COLUMN lastConfirmedActiveTimeMillis INTEGER",
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE usage_sessions
                    SET endTimeMillis = CASE
                        WHEN lastConfirmedActiveTimeMillis IS NULL
                            OR lastConfirmedActiveTimeMillis < startTimeMillis
                        THEN startTimeMillis
                        ELSE lastConfirmedActiveTimeMillis
                    END
                    WHERE endTimeMillis IS NULL
                    """.trimIndent(),
                )
                installOpenSessionIntegrityTriggers(db)
            }
        }

        internal val OPEN_SESSION_INTEGRITY_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                installOpenSessionIntegrityTriggers(db)
            }
        }

        private fun installOpenSessionIntegrityTriggers(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TRIGGER usage_sessions_one_open_before_insert
                BEFORE INSERT ON usage_sessions
                WHEN NEW.endTimeMillis IS NULL
                    AND EXISTS (
                        SELECT 1
                        FROM usage_sessions
                        WHERE platform = NEW.platform
                            AND usageType = NEW.usageType
                            AND endTimeMillis IS NULL
                    )
                BEGIN
                    SELECT RAISE(ABORT, '$OPEN_SESSION_INVARIANT_MESSAGE');
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER usage_sessions_one_open_before_update
                BEFORE UPDATE OF platform, usageType, endTimeMillis, id ON usage_sessions
                WHEN NEW.endTimeMillis IS NULL
                    AND EXISTS (
                        SELECT 1
                        FROM usage_sessions
                        WHERE platform = NEW.platform
                            AND usageType = NEW.usageType
                            AND endTimeMillis IS NULL
                            AND id != OLD.id
                            AND id != NEW.id
                    )
                BEGIN
                    SELECT RAISE(ABORT, '$OPEN_SESSION_INVARIANT_MESSAGE');
                END
                """.trimIndent(),
            )
        }
    }
}
