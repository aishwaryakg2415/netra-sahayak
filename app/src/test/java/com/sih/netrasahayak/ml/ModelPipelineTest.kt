package com.sih.netrasahayak.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelPipelineTest {

    private fun syntheticFundus(w: Int, h: Int): RgbImage {
        val data = ByteArray(w * h * 3)
        val cx = w / 2.0
        val cy = h / 2.0
        val r = 0.45 * minOf(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val d = kotlin.math.hypot(x - cx, y - cy)
            if (d < r) {
                val shade = 1 - 0.5 * (d / r) * (d / r)
                val stripe = if ((x / 7 + y / 11) % 5 == 0) 0.6 else 1.0
                val i = (y * w + x) * 3
                data[i] = (200 * shade * stripe).toInt().toByte()
                data[i + 1] = (90 * shade * stripe).toInt().toByte()
                data[i + 2] = (40 * shade * stripe).toInt().toByte()
            }
        }
        return RgbImage(w, h, data)
    }

    @Test
    fun preprocessProducesModelSizedInput() {
        val pre = RetinaPreprocessor.preprocess(syntheticFundus(1500, 1000))
        assertEquals(RetinaPreprocessor.OUT_SIZE, pre.image.width)
        val input = pre.toModelInput()
        assertEquals(384 * 384 * 3, input.size)
        assertTrue(input.all { it in 0f..255f })
    }

    @Test
    fun blackImageIsNotAcceptedForScreening() {
        val result = ImageQualityAssessor.assess(RgbImage(600, 600, ByteArray(600 * 600 * 3)))
        assertTrue(result.status != QualityStatus.ACCEPTED_FOR_SCREENING)
        assertEquals(null, result.preprocessed)
    }

    @Test
    fun uniformGreyPhotoIsUnsupported() {
        val grey = RgbImage(640, 480, ByteArray(640 * 480 * 3) { 128.toByte() })
        assertEquals(QualityStatus.UNSUPPORTED_IMAGE, ImageQualityAssessor.assess(grey).status)
    }

    @Test
    fun camIsWeightedSumOfFeatureMapsWithRelu() {
        // 2x1 grid, 2 channels, 5 classes.
        val features = floatArrayOf(1f, 0f, 0f, 1f)
        val weights = FloatArray(10).also {
            it[0 * 5 + 2] = 2f   // channel 0 supports class 2
            it[1 * 5 + 2] = -1f  // channel 1 opposes class 2
        }
        val cam = ClassActivationMap.compute(features, 2, 1, 2, weights, 5, 2)
        assertArrayEquals(floatArrayOf(1f, 0f), cam, 1e-6f) // [2, -1] -> ReLU -> normalised
    }

    @Test
    fun heatmapIsTransparentOutsideRetina() {
        val pre = RetinaPreprocessor.preprocess(syntheticFundus(800, 600))
        val cam = FloatArray(12 * 12) { 1f }
        val argb = ClassActivationMap.renderOnWorkingFrame(cam, 12, 12, pre)
        assertEquals(pre.geometry.workWidth * pre.geometry.workHeight, argb.size)
        assertEquals(0, argb[0]) // corner is outside the retina
        val centre = (pre.geometry.workHeight / 2) * pre.geometry.workWidth + pre.geometry.workWidth / 2
        assertTrue((argb[centre] ushr 24) > 0)
    }

    @Test
    fun configRejectsMismatchedPreprocessing() {
        val json = """
            {"schema":1,"model_version":"t","architecture":"a","quantization":"fp16",
             "files":{"model":{"name":"m","sha256":"x"},"cam_weights":{"name":"c","sha256":"y"}},
             "input":{"signature":"serving_default","name":"image","shape":[1,224,224,3]},
             "outputs":{"logits":[1,5],"features":[1,7,7,8]},
             "preprocessing":{"work_max_side":1024,"mask_threshold":20,"out_size":224},
             "classes":["a","b","c","d","e"],"calibration":{"temperature":1.2},
             "decision":{"referable_min_grade":2,"referable_threshold":0.3,"urgent_threshold":0.2,"review_min_confidence":0.5},
             "quality_thresholds":{"min_side_px":256,"retina_fraction_min":0.2,"corner_dark_min":0.5,"red_ratio_min":1.1,
               "brightness_min":25,"brightness_max":200,"saturated_max":0.1,"sharpness_min":2}}
        """.trimIndent()
        val cfg = ModelConfig.parse(json)
        assertEquals(1.2, cfg.calibration.temperature, 1e-9)
        try {
            cfg.checkCompatible()
            fail("a 224 px model must be rejected by a 384 px preprocessor")
        } catch (expected: IllegalArgumentException) {
        }
        val ok = ModelConfig.parse(json.replace("224", "384"))
        ok.checkCompatible()
        assertEquals(0.3, ok.decisionConfig().referableThreshold, 1e-9)
    }
}
