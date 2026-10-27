package com.sih.netrasahayak.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * On-device DR classifier backed by LiteRT (Interpreter API, signature runner).
 *
 * Everything it needs ships inside the APK under assets/model/:
 *   model_config.json  - versions, thresholds, checksums
 *   netra_dr.tflite    - image[1,384,384,3] -> {logits[1,5], features[1,h,w,k]}
 *   cam_weights.bin    - classifier head weights for the explanation heatmap
 *
 * No network access is involved at any point. GPU acceleration is used when the
 * device reports a compatible GPU; otherwise (or if the delegate fails) the
 * model runs on the CPU with XNNPACK.
 */
class DrClassifier private constructor(
    val config: ModelConfig,
    private val interpreter: Interpreter,
    private val gpuDelegate: GpuDelegate?,
    val camWeights: FloatArray,
    val featureShape: IntArray // h, w, k
) : AutoCloseable {

    val usesGpu: Boolean get() = gpuDelegate != null

    class Output(val logits: FloatArray, val features: FloatArray)

    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(4 * RetinaPreprocessor.OUT_SIZE * RetinaPreprocessor.OUT_SIZE * 3)
            .order(ByteOrder.nativeOrder())
    private val logitsBuffer = ByteBuffer.allocateDirect(4 * 5).order(ByteOrder.nativeOrder())
    private val featureBuffer = ByteBuffer.allocateDirect(4 * featureShape[0] * featureShape[1] * featureShape[2])
        .order(ByteOrder.nativeOrder())

    @Synchronized
    fun run(input: FloatArray): Output {
        require(input.size * 4 == inputBuffer.capacity()) { "input size mismatch" }
        inputBuffer.clear()
        inputBuffer.asFloatBuffer().put(input)
        logitsBuffer.clear()
        featureBuffer.clear()
        interpreter.runSignature(
            mapOf(config.input.name to inputBuffer),
            mapOf("logits" to logitsBuffer, "features" to featureBuffer),
            config.input.signature
        )
        logitsBuffer.rewind()
        featureBuffer.rewind()
        val logits = FloatArray(5).also { logitsBuffer.asFloatBuffer().get(it) }
        val features = FloatArray(featureBuffer.capacity() / 4).also { featureBuffer.asFloatBuffer().get(it) }
        for (v in logits) check(v.isFinite()) { "model produced a non-finite output" }
        return Output(logits, features)
    }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }

    sealed class LoadError(message: String) : Exception(message) {
        class Missing(message: String) : LoadError(message)
        class IntegrityFailed(message: String) : LoadError(message)
        class Incompatible(message: String) : LoadError(message)
    }

    companion object {
        private const val TAG = "DrClassifier"
        const val ASSET_DIR = "model"

        /**
         * Loads and verifies the bundled model. Throws [LoadError] - never returns a
         * half-working classifier.
         */
        fun load(context: Context, allowGpu: Boolean = true): DrClassifier {
            val assets = context.assets
            val configJson = try {
                assets.open("$ASSET_DIR/model_config.json").bufferedReader().use { it.readText() }
            } catch (e: FileNotFoundException) {
                throw LoadError.Missing("model_config.json not bundled")
            }
            val config = try {
                ModelConfig.parse(configJson).also { it.checkCompatible() }
            } catch (e: Exception) {
                throw LoadError.Incompatible(e.message ?: "invalid model_config.json")
            }

            val modelBytes = mapAsset(context, "$ASSET_DIR/${config.files.model.name}")
            if (!sha256(modelBytes).equals(config.files.model.sha256, ignoreCase = true)) {
                throw LoadError.IntegrityFailed("model checksum mismatch")
            }
            val camBytes = readAsset(context, "$ASSET_DIR/${config.files.camWeights.name}")
            if (!sha256(ByteBuffer.wrap(camBytes)).equals(config.files.camWeights.sha256, ignoreCase = true)) {
                throw LoadError.IntegrityFailed("explanation weights checksum mismatch")
            }
            val camWeights = FloatArray(camBytes.size / 4).also {
                ByteBuffer.wrap(camBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
            }
            val feat = config.outputs["features"] ?: throw LoadError.Incompatible("no features output")
            val featureShape = intArrayOf(feat[1], feat[2], feat[3])
            if (camWeights.size != featureShape[2] * 5) throw LoadError.Incompatible("explanation weights shape")

            var delegate: GpuDelegate? = null
            val interpreter = try {
                if (allowGpu && runCatching { CompatibilityList().isDelegateSupportedOnThisDevice }.getOrDefault(false)) {
                    delegate = GpuDelegate(CompatibilityList().bestOptionsForThisDevice)
                    Interpreter(modelBytes, Interpreter.Options().addDelegate(delegate))
                } else {
                    cpuInterpreter(modelBytes)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "GPU delegate unavailable, using CPU", t)
                delegate?.close()
                delegate = null
                cpuInterpreter(modelBytes)
            }
            return DrClassifier(config, interpreter, delegate, camWeights, featureShape)
        }

        private fun cpuInterpreter(model: ByteBuffer) =
            Interpreter(model, Interpreter.Options().setNumThreads(4).setUseXNNPACK(true))

        private fun mapAsset(context: Context, path: String): MappedByteBuffer = try {
            context.assets.openFd(path).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        } catch (e: FileNotFoundException) {
            throw LoadError.Missing("$path not bundled")
        }

        private fun readAsset(context: Context, path: String): ByteArray = try {
            context.assets.open(path).use { it.readBytes() }
        } catch (e: FileNotFoundException) {
            throw LoadError.Missing("$path not bundled")
        }

        fun sha256(buffer: ByteBuffer): String {
            val md = MessageDigest.getInstance("SHA-256")
            val dup = buffer.duplicate()
            dup.rewind()
            md.update(dup)
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
