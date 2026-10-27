package com.sih.netrasahayak.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.sih.netrasahayak.R
import com.sih.netrasahayak.ml.ClassActivationMap
import com.sih.netrasahayak.ml.DecisionCategory
import com.sih.netrasahayak.ml.DrClassifier
import com.sih.netrasahayak.ml.ImageQualityAssessor
import com.sih.netrasahayak.ml.QualityStatus
import com.sih.netrasahayak.ml.ReferralPolicy
import com.sih.netrasahayak.ml.RetinalImageDecoder
import com.sih.netrasahayak.model.AppError
import com.sih.netrasahayak.model.DrClass
import com.sih.netrasahayak.model.Outcome
import com.sih.netrasahayak.model.PatientDetails
import com.sih.netrasahayak.model.ScreeningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Fully offline screening with the LiteRT model bundled in the APK.
 *
 *   decode -> quality gate -> preprocess -> LiteRT -> temperature-scaled softmax
 *          -> ReferralPolicy -> class-activation heatmap -> ScreeningResult
 *
 * Failure handling is strict: a missing/corrupt model, an ungradable image or an
 * inference error returns an [AppError]. There is no fallback that invents a
 * grade, and an ungradable image can never come back as "No DR".
 *
 * Logs never contain patient identifiers or image paths.
 */
class LocalInferenceRepository(private val context: Context) : InferenceRepository {

    private val tag = "LocalInference"

    override val sourceName: String get() = context.getString(R.string.source_on_device)
    override val requiresInternet: Boolean = false

    private val loadLock = Mutex()
    @Volatile private var classifier: DrClassifier? = null
    @Volatile private var loadError: AppError? = null

    /** Loads and checksum-verifies the model once. Safe to call repeatedly. */
    suspend fun initializeModel(): Outcome<DrClassifier> = withContext(Dispatchers.IO) {
        loadLock.withLock {
            classifier?.let { return@withLock Outcome.Success(it) }
            loadError?.let { return@withLock Outcome.Failure(it) }
            try {
                val c = DrClassifier.load(context)
                Log.i(tag, "Model ${c.config.modelVersion} loaded (gpu=${c.usesGpu})")
                classifier = c
                Outcome.Success(c)
            } catch (e: DrClassifier.LoadError) {
                Log.w(tag, "Model unavailable: ${e.javaClass.simpleName}: ${e.message}")
                val err = when (e) {
                    is DrClassifier.LoadError.Missing -> AppError.ModelNotInstalled
                    is DrClassifier.LoadError.IntegrityFailed,
                    is DrClassifier.LoadError.Incompatible -> AppError.ModelIntegrityFailed
                }
                loadError = err
                Outcome.Failure(err)
            } catch (t: Throwable) {
                Log.w(tag, "Model failed to initialise", t)
                Outcome.Failure(AppError.ModelIntegrityFailed)
            }
        }
    }

    override suspend fun analyze(
        imageUri: Uri,
        patient: PatientDetails
    ): Outcome<ScreeningResult> = withContext(Dispatchers.Default) {
        val start = SystemClock.elapsedRealtime()
        val model = when (val loaded = initializeModel()) {
            is Outcome.Failure -> return@withContext loaded
            is Outcome.Success -> loaded.data
        }
        val config = model.config

        val rgb = try {
            RetinalImageDecoder.decode(context, imageUri)
        } catch (oom: OutOfMemoryError) {
            return@withContext Outcome.Failure(AppError.ImageTooLarge)
        } catch (t: Throwable) {
            Log.w(tag, "Decode failed: ${t.javaClass.simpleName}")
            null
        } ?: return@withContext Outcome.Failure(AppError.InvalidImage)

        // 1. Quality gate - nothing below runs for an ungradable image.
        val quality = ImageQualityAssessor.assess(rgb, config.qualityThresholds())
        when (quality.status) {
            QualityStatus.RETAKE_IMAGE -> return@withContext Outcome.Failure(AppError.RetakeImage(quality.reasons))
            QualityStatus.UNSUPPORTED_IMAGE -> return@withContext Outcome.Failure(AppError.NotFundusImage)
            else -> Unit
        }
        val pre = quality.preprocessed ?: return@withContext Outcome.Failure(AppError.InvalidImage)

        // 2. Inference.
        val output = try {
            model.run(pre.toModelInput())
        } catch (t: Throwable) {
            Log.w(tag, "Inference failed: ${t.javaClass.simpleName}")
            return@withContext Outcome.Failure(AppError.InferenceFailed)
        }
        val probs = ReferralPolicy.softmax(output.logits, config.calibration.temperature)
        val decision = ReferralPolicy.decide(probs, config.decisionConfig())
        val drClass = DrClass.entries.first { it.severity == decision.grade }

        // 3. Explanation (failure here never blocks the result; it is just omitted).
        val heatmap = runCatching {
            val (fh, fw, k) = model.featureShape.let { Triple(it[0], it[1], it[2]) }
            val cam = ClassActivationMap.compute(output.features, fh, fw, k, model.camWeights, 5, decision.grade)
            saveHeatmap(ClassActivationMap.renderOnWorkingFrame(cam, fh, fw, pre), pre.geometry.workWidth, pre.geometry.workHeight)
        }.onFailure { Log.w(tag, "Explanation not generated: ${it.javaClass.simpleName}") }.getOrNull()

        val recommendation = when (decision.category) {
            DecisionCategory.URGENT_REFERRAL -> context.getString(R.string.rec_urgent_referral)
            DecisionCategory.REFERRAL -> context.getString(R.string.rec_referral)
            DecisionCategory.HUMAN_REVIEW -> context.getString(R.string.rec_human_review)
            // Routine: keep the grade's own (localised) advice.
            DecisionCategory.ROUTINE_RESCREEN -> drClass.defaultRecommendation
        } + if (decision.needsReview && decision.category != DecisionCategory.HUMAN_REVIEW) {
            context.getString(R.string.rec_review_suffix)
        } else ""

        Outcome.Success(
            ScreeningResult(
                drClass = drClass,
                rawPrediction = drClass.apiLabel,
                confidence = probs[decision.grade],
                heatmapUrl = heatmap?.absolutePath,
                recommendation = recommendation,
                screeningId = UUID.randomUUID().toString(),
                classScores = probs.toList(),
                qualityStatus = if (decision.needsReview) QualityStatus.REVIEW_REQUIRED.name else quality.status.name,
                referable = decision.referable,
                urgent = decision.urgent,
                needsReview = decision.needsReview,
                decisionCategory = decision.category.name,
                modelVersion = config.modelVersion,
                processingMillis = SystemClock.elapsedRealtime() - start
            )
        )
    }

    private fun saveHeatmap(argb: IntArray, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        val dir = File(context.filesDir, EXPLANATION_DIR).apply { mkdirs() }
        val file = File(dir, "cam_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    companion object {
        /** App-private directory for explanation heatmaps (deleted with their record). */
        const val EXPLANATION_DIR = "explanations"
    }
}
