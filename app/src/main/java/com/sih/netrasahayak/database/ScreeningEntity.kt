package com.sih.netrasahayak.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One screening, stored on the phone. This is the offline source of truth:
 * history is fully readable with no internet connection.
 */
@Entity(tableName = "screenings")
data class ScreeningEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "patient_id") val patientId: String,
    @ColumnInfo(name = "age") val age: Int,
    @ColumnInfo(name = "gender") val gender: String? = null,
    @ColumnInfo(name = "diabetes_duration_years") val diabetesDurationYears: Int? = null,

    /** Absolute path of the retinal image copied into the app's private storage. */
    @ColumnInfo(name = "image_path") val imagePath: String? = null,

    /** Grad-CAM heatmap location (http URL from the backend, or a local file). */
    @ColumnInfo(name = "heatmap_url") val heatmapUrl: String? = null,

    @ColumnInfo(name = "prediction") val prediction: String,
    @ColumnInfo(name = "confidence") val confidence: Float,
    @ColumnInfo(name = "recommendation") val recommendation: String,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),

    /** false until the record has been pushed to a server. Drives the Sync screen. */
    @ColumnInfo(name = "synced") val synced: Boolean = false,

    // ---- Added in schema v2 (on-device AI). All nullable / defaulted so v1 rows migrate untouched.

    @ColumnInfo(name = "screening_uuid") val screeningUuid: String? = null,
    /** Calibrated scores for grades 0..4, comma-separated. */
    @ColumnInfo(name = "class_scores") val classScores: String? = null,
    @ColumnInfo(name = "quality_status") val qualityStatus: String? = null,
    @ColumnInfo(name = "referable") val referable: Boolean? = null,
    @ColumnInfo(name = "urgent") val urgent: Boolean? = null,
    @ColumnInfo(name = "needs_review", defaultValue = "0") val needsReview: Boolean = false,
    @ColumnInfo(name = "decision_category") val decisionCategory: String? = null,
    @ColumnInfo(name = "model_version") val modelVersion: String? = null
)
