package com.sih.netrasahayak.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ScreeningEntity::class],
    version = 2,
    exportSchema = true
)
abstract class NetraDatabase : RoomDatabase() {

    abstract fun screeningDao(): ScreeningDao

    companion object {
        private const val DATABASE_NAME = "netra_sahayak.db"

        /** v1 -> v2: on-device AI fields. Additive only - existing history is kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE screenings ADD COLUMN screening_uuid TEXT")
                db.execSQL("ALTER TABLE screenings ADD COLUMN class_scores TEXT")
                db.execSQL("ALTER TABLE screenings ADD COLUMN quality_status TEXT")
                db.execSQL("ALTER TABLE screenings ADD COLUMN referable INTEGER")
                db.execSQL("ALTER TABLE screenings ADD COLUMN urgent INTEGER")
                db.execSQL("ALTER TABLE screenings ADD COLUMN needs_review INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE screenings ADD COLUMN decision_category TEXT")
                db.execSQL("ALTER TABLE screenings ADD COLUMN model_version TEXT")
            }
        }

        @Volatile
        private var instance: NetraDatabase? = null

        fun getInstance(context: Context): NetraDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NetraDatabase::class.java,
                    DATABASE_NAME
                )
                    // Screening history is patient data: upgrades must migrate, never wipe.
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
