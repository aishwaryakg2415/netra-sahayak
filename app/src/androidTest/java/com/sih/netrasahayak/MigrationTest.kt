package com.sih.netrasahayak

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sih.netrasahayak.database.NetraDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screening history written by app version 1 must survive the upgrade to the
 * on-device AI schema (v2). The v1 table is created with the exact SQL Room
 * generated for version 1 of ScreeningEntity.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val v1Sql = "CREATE TABLE IF NOT EXISTS `screenings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`patient_id` TEXT NOT NULL, `age` INTEGER NOT NULL, `gender` TEXT, `diabetes_duration_years` INTEGER, " +
        "`image_path` TEXT, `heatmap_url` TEXT, `prediction` TEXT NOT NULL, `confidence` REAL NOT NULL, " +
        "`recommendation` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `synced` INTEGER NOT NULL)"

    @Test
    fun migrate1To2KeepsHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-test.db"
        context.deleteDatabase(name)

        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL(v1Sql)
            db.insert("screenings", null, ContentValues().apply {
                put("patient_id", "P-001"); put("age", 54); put("gender", "female")
                put("prediction", "Moderate Diabetic Retinopathy"); put("confidence", 0.81f)
                put("recommendation", "Clinical evaluation"); put("created_at", 1_700_000_000_000L); put("synced", 0)
            })
            db.version = 1
        }

        val room = Room.databaseBuilder(context, NetraDatabase::class.java, name)
            .addMigrations(NetraDatabase.MIGRATION_1_2)
            .build()
        val row = room.screeningDao().getUnsynced().single()
        assertEquals("P-001", row.patientId)
        assertEquals(54, row.age)
        assertEquals("Moderate Diabetic Retinopathy", row.prediction)
        assertEquals(0.81f, row.confidence, 1e-6f)
        assertNull(row.modelVersion)
        assertNull(row.referable)
        assertFalse(row.needsReview)
        room.close()
        context.deleteDatabase(name)
    }
}
