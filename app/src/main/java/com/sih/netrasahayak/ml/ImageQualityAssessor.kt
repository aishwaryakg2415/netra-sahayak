package com.sih.netrasahayak.ml

import kotlin.math.min

/**
 * Kotlin port of ml/netra_ml/quality.py. Deterministic heuristics that catch
 * obvious problems; they do not prove an image is gradable.
 */
enum class QualityStatus { ACCEPTED_FOR_SCREENING, RETAKE_IMAGE, UNSUPPORTED_IMAGE, REVIEW_REQUIRED }

data class QualityThresholds(
    val minSidePx: Int = 256,
    val retinaFractionMin: Double = 0.20,
    val cornerDarkMin: Double = 0.50,
    val redRatioMin: Double = 1.10,
    val brightnessMin: Double = 25.0,
    val brightnessMax: Double = 200.0,
    val saturatedMax: Double = 0.10,
    val sharpnessMin: Double = 2.0
)

data class QualityMetrics(
    val minSidePx: Int,
    val retinaFraction: Double,
    val cornerDarkFraction: Double,
    val redRatio: Double,
    val meanBrightness: Double,
    val saturatedFraction: Double,
    val sharpness: Double
)

data class QualityResult(
    val status: QualityStatus,
    val reasons: List<String>,
    val metrics: QualityMetrics?,
    val preprocessed: Preprocessed?
)

object ImageQualityAssessor {

    private const val DARK_IMAGE_MEAN = 30

    fun assess(rgb: RgbImage, t: QualityThresholds = QualityThresholds()): QualityResult {
        val pre = try {
            RetinaPreprocessor.preprocess(rgb)
        } catch (e: NoRetinaFoundException) {
            val work = RetinaPreprocessor.workingCopy(rgb)
            var total = 0L
            for (b in work.data) total += b.toInt() and 0xFF
            return if (total < DARK_IMAGE_MEAN.toLong() * 3 * work.width * work.height) {
                QualityResult(QualityStatus.RETAKE_IMAGE, listOf("too_dark", "retina_not_found"), null, null)
            } else {
                QualityResult(QualityStatus.UNSUPPORTED_IMAGE, listOf("retina_not_found"), null, null)
            }
        }
        val m = measure(pre, min(rgb.width, rgb.height))
        return decide(m, t, pre)
    }

    fun measure(pre: Preprocessed, originalMinSide: Int): QualityMetrics {
        val img = pre.image
        val n = img.width
        val d = img.data
        val outMask = BooleanArray(n * n)
        var count = 0L
        var rSum = 0L
        var gSum = 0L
        var totalSum = 0L
        var saturated = 0L
        for (i in 0 until n * n) {
            val r = d[i * 3].toInt() and 0xFF
            val g = d[i * 3 + 1].toInt() and 0xFF
            val b = d[i * 3 + 2].toInt() and 0xFF
            val total = r + g + b
            if (total > 3 * RetinaPreprocessor.MASK_THRESHOLD) {
                outMask[i] = true
                count++
                rSum += r
                gSum += g
                totalSum += total
                if (maxOf(r, g, b) >= 250) saturated++
            }
        }
        val redRatio = if (count == 0L) 0.0 else rSum.toDouble() / maxOf(gSum, 1L)
        val brightness = if (count == 0L) 0.0 else totalSum.toDouble() / (3 * count)
        val satFrac = if (count == 0L) 0.0 else saturated.toDouble() / count

        var maskCount = 0L
        for (v in pre.mask) if (v) maskCount++

        return QualityMetrics(
            minSidePx = originalMinSide,
            retinaFraction = maskCount.toDouble() / pre.mask.size,
            cornerDarkFraction = cornerDarkFraction(pre.mask, pre.work.width, pre.work.height),
            redRatio = redRatio,
            meanBrightness = brightness,
            saturatedFraction = satFrac,
            sharpness = laplacianVariance(img, outMask)
        )
    }

    fun decide(m: QualityMetrics, t: QualityThresholds, pre: Preprocessed? = null): QualityResult {
        val unsupported = mutableListOf<String>()
        val retake = mutableListOf<String>()
        if (m.retinaFraction < t.retinaFractionMin) unsupported += "retina_not_found"
        if (m.cornerDarkFraction < t.cornerDarkMin) unsupported += "not_fundus_layout"
        if (m.redRatio < t.redRatioMin) unsupported += "not_fundus_colour"
        if (m.minSidePx < t.minSidePx) retake += "too_small"
        if (m.meanBrightness < t.brightnessMin) retake += "too_dark"
        if (m.meanBrightness > t.brightnessMax) retake += "too_bright"
        if (m.saturatedFraction > t.saturatedMax) retake += "glare"
        if (m.sharpness < t.sharpnessMin) retake += "blurred"
        return when {
            unsupported.isNotEmpty() && "too_dark" !in retake ->
                QualityResult(QualityStatus.UNSUPPORTED_IMAGE, unsupported + retake, m, pre)
            unsupported.isNotEmpty() || retake.isNotEmpty() ->
                QualityResult(QualityStatus.RETAKE_IMAGE, retake + unsupported, m, pre)
            else -> QualityResult(QualityStatus.ACCEPTED_FOR_SCREENING, emptyList(), m, pre)
        }
    }

    fun cornerDarkFraction(mask: BooleanArray, w: Int, h: Int): Double {
        val ch = maxOf(1, (h + 9) / 10)
        val cw = maxOf(1, (w + 9) / 10)
        var dark = 0L
        var total = 0L
        val ys = listOf(0 until ch, (h - ch) until h)
        val xs = listOf(0 until cw, (w - cw) until w)
        for (yr in ys) for (xr in xs) for (y in yr) for (x in xr) {
            total++
            if (!mask[y * w + x]) dark++
        }
        return dark.toDouble() / total
    }

    /** Variance of the 4-neighbour Laplacian of the green channel inside the central disk. */
    fun laplacianVariance(img: RgbImage, outMask: BooleanArray): Double {
        val n = img.width
        var count = 0L
        var s = 0L
        var s2 = 0L
        val limit = (42L * n) * (42L * n)
        for (y in 1 until n - 1) {
            val dy = (2L * y + 1 - n)
            for (x in 1 until n - 1) {
                val dx = (2L * x + 1 - n)
                if ((dy * dy + dx * dx) * 2500 > limit || !outMask[y * n + x]) continue
                val lap = img.g(x, y - 1) + img.g(x, y + 1) + img.g(x - 1, y) + img.g(x + 1, y) - 4 * img.g(x, y)
                count++
                s += lap
                s2 += lap.toLong() * lap
            }
        }
        if (count == 0L) return 0.0
        val mean = s.toDouble() / count
        return s2.toDouble() / count - mean * mean
    }
}
