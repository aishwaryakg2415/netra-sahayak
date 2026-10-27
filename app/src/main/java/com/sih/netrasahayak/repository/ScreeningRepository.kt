package com.sih.netrasahayak.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sih.netrasahayak.camera.ImageUtils
import com.sih.netrasahayak.database.ScreeningDao
import com.sih.netrasahayak.database.ScreeningEntity
import com.sih.netrasahayak.model.AppError
import com.sih.netrasahayak.model.Outcome
import com.sih.netrasahayak.model.PatientDetails
import com.sih.netrasahayak.model.ScreeningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Local screening history. Everything here works with no internet.
 */
class ScreeningRepository(
    private val context: Context,
    private val dao: ScreeningDao
) {

    private val tag = "ScreeningRepository"

    fun observeHistory(): Flow<List<ScreeningEntity>> = dao.observeAll()

    fun observePendingSyncCount(): Flow<Int> = dao.observePendingCount()

    suspend fun getById(id: Long): ScreeningEntity? = withContext(Dispatchers.IO) {
        runCatching { dao.getById(id) }.getOrNull()
    }

    /**
     * Saves a finished screening. The retinal image is copied into app-private
     * storage first so it is still viewable in history later.
     */
    suspend fun save(
        patient: PatientDetails,
        imageUri: Uri,
        result: ScreeningResult
    ): Outcome<Long> = withContext(Dispatchers.IO) {
        try {
            val storedImage = ImageUtils.copyOriginalToAppStorage(context, imageUri)
            val id = dao.insert(
                ScreeningEntity(
                    patientId = patient.patientId.trim(),
                    age = patient.age,
                    gender = patient.gender?.apiValue,
                    diabetesDurationYears = patient.diabetesDurationYears,
                    imagePath = storedImage?.absolutePath,
                    heatmapUrl = result.heatmapUrl,
                    prediction = result.drClass.apiLabel,
                    confidence = result.confidence,
                    recommendation = result.recommendation,
                    synced = false,
                    screeningUuid = result.screeningId,
                    classScores = result.classScores.takeIf { it.isNotEmpty() }
                        ?.joinToString(",") { "%.5f".format(java.util.Locale.US, it) },
                    qualityStatus = result.qualityStatus,
                    referable = result.referable,
                    urgent = result.urgent,
                    needsReview = result.needsReview,
                    decisionCategory = result.decisionCategory,
                    modelVersion = result.modelVersion
                )
            )
            Outcome.Success(id)
        } catch (t: Throwable) {
            Log.w(tag, "Could not save screening", t)
            Outcome.Failure(AppError.StorageError)
        }
    }

    /** Deletes the record and the retinal image / heatmap files it owns in app-private storage. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        runCatching {
            dao.getById(id)?.let { entity ->
                listOfNotNull(entity.imagePath, entity.heatmapUrl).forEach { deleteOwnedFile(it) }
            }
            dao.deleteById(id)
        }.onFailure { Log.w(tag, "Could not delete screening", it) }
        Unit
    }

    private fun deleteOwnedFile(path: String) {
        val file = java.io.File(path)
        val root = context.filesDir.canonicalPath + java.io.File.separator
        if (file.canonicalPath.startsWith(root)) file.delete()
    }
}
