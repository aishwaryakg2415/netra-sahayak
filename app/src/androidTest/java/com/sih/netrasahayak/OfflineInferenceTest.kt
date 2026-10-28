package com.sih.netrasahayak

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.sih.netrasahayak.ml.DrClassifier
import com.sih.netrasahayak.model.AppError
import com.sih.netrasahayak.model.Outcome
import com.sih.netrasahayak.model.PatientDetails
import com.sih.netrasahayak.repository.LocalInferenceRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real on-device pipeline. Run it with networking disabled to verify
 * offline operation (tools/verify_offline.sh puts the device in airplane mode
 * first and records the network state in the test log).
 */
@RunWith(AndroidJUnit4::class)
class OfflineInferenceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val patient = PatientDetails(patientId = "TEST-1", age = 50, gender = null, diabetesDurationYears = null)

    private fun modelBundled() = context.assets.list(DrClassifier.ASSET_DIR)?.contains("model_config.json") == true

    private fun copyTestAsset(name: String): Uri {
        val f = File(context.cacheDir, name)
        testAssets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(f)
    }

    private fun networkState(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return if (cm.activeNetwork == null) "offline" else "online"
    }

    @Test
    fun missingModelNeverProducesAResult(): Unit = runBlocking {
        assumeTrue("model is bundled; covered by the inference test", !modelBundled())
        val img = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFB05020.toInt()) }
        val f = File(context.cacheDir, "any.png")
        f.outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val outcome = LocalInferenceRepository(context).analyze(Uri.fromFile(f), patient)
        assertEquals(Outcome.Failure(AppError.ModelNotInstalled), outcome)
    }

    @Test
    fun goldenImageMatchesTrainingPipelineOutput(): Unit = runBlocking {
        assumeTrue("model assets not bundled in this build", modelBundled())
        android.util.Log.i("OfflineInferenceTest", "network during test: ${networkState()}")
        val repo = LocalInferenceRepository(context)
        val classifier = (repo.initializeModel() as Outcome.Success).data

        var n = 0
        while (testAssets.list("")!!.contains("golden_${n}.json")) {
            val golden = JsonParser.parseReader(testAssets.open("golden_${n}.json").reader()).asJsonObject
            val outcome = repo.analyze(copyTestAsset("golden_${n}_input.png"), patient)
            if (golden["quality"].asString != "ACCEPTED_FOR_SCREENING") {
                assertTrue(outcome is Outcome.Failure)
            } else {
                val result = (outcome as Outcome.Success).data
                android.util.Log.i("OfflineInferenceTest", "golden $n: grade ${result.drClass.severity} in ${result.processingMillis} ms (decode+quality+inference+CAM)")
                val expected = golden.getAsJsonArray("probs").map { it.asFloat }
                assertEquals(5, result.classScores.size)
                assertEquals(1.0, result.classScores.sum().toDouble(), 1e-4)
                // PNG decode differences are impossible (lossless), so only float/delegate noise remains.
                for (i in 0 until 5) assertEquals("golden $n class $i", expected[i], result.classScores[i], 0.03f)
                assertEquals(golden.getAsJsonObject("decision")["grade"].asInt, result.drClass.severity)
                assertEquals(classifier.config.modelVersion, result.modelVersion)
                assertNotNull(result.heatmapUrl)
                assertTrue(File(result.heatmapUrl!!).length() > 0)
            }
            n++
        }
        assertTrue("no golden cases bundled", n > 0)
    }

    @Test
    fun nonFundusImageIsRejectedNotGraded(): Unit = runBlocking {
        assumeTrue(modelBundled())
        val grey = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
        val f = File(context.cacheDir, "grey.png")
        f.outputStream().use { grey.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val outcome = LocalInferenceRepository(context).analyze(Uri.fromFile(f), patient)
        assertEquals(Outcome.Failure(AppError.NotFundusImage), outcome)
    }

    @Test
    fun corruptedFileIsRejected(): Unit = runBlocking {
        assumeTrue(modelBundled())
        val f = File(context.cacheDir, "corrupt.jpg").apply { writeText("not an image") }
        val outcome = LocalInferenceRepository(context).analyze(Uri.fromFile(f), patient)
        assertEquals(Outcome.Failure(AppError.InvalidImage), outcome)
    }

    @Test
    fun cpuFallbackProducesSameScores() {
        assumeTrue(modelBundled())
        val gpu = DrClassifier.load(context, allowGpu = true)
        val cpu = DrClassifier.load(context, allowGpu = false)
        val input = FloatArray(384 * 384 * 3) { (it % 251).toFloat() }
        val a = gpu.run(input).logits
        val b = cpu.run(input).logits
        for (i in a.indices) assertEquals(a[i], b[i], 0.1f)
        gpu.close(); cpu.close()
    }

    @Test
    fun releaseManifestHasNoInternetPermission() {
        // Debug builds add INTERNET for the optional dev backend; release must not have it.
        val granted = context.packageManager.checkPermission(android.Manifest.permission.INTERNET, context.packageName)
        if (!BuildConfig.DEBUG) assertEquals(PackageManager.PERMISSION_DENIED, granted)
    }
}
