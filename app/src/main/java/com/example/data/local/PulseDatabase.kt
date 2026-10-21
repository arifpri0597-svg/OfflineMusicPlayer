package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SongEntity::class,
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        PlaybackStateEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class PulseDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao

    companion object {
        @Volatile
        private var INSTANCE: PulseDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playback_state` (
                        `id` INTEGER NOT NULL,
                        `songId` INTEGER NOT NULL,
                        `contentUriString` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `albumArtUriString` TEXT,
                        `positionMs` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `queueSongIdsCsv` TEXT NOT NULL,
                        `currentIndex` INTEGER NOT NULL,
                        `isPlaying` INTEGER NOT NULL,
                        `shuffleEnabled` INTEGER NOT NULL,
                        `repeatMode` TEXT NOT NULL,
                        `playbackSpeed` REAL NOT NULL DEFAULT 1.0,
                        `isExternal` INTEGER NOT NULL DEFAULT 0,
                        `lastUpdated` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE `playback_state` ADD COLUMN `playbackSpeed` REAL NOT NULL DEFAULT 1.0")
                } catch (_: Exception) {
                }
                try {
                    db.execSQL("ALTER TABLE `playback_state` ADD COLUMN `isExternal` INTEGER NOT NULL DEFAULT 0")
                } catch (_: Exception) {
                }
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("DROP TABLE IF EXISTS `history`")
                } catch (_: Exception) {
                }
            }
        }

        fun getInstance(context: Context): PulseDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PulseDatabase::class.java,
                    "pulse_music_database.db"
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

