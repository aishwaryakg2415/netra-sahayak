package com.sih.netrasahayak.ml

import kotlin.math.floor
import kotlin.math.min

/**
 * Explanation heatmap computed from the model's own computation.
 *
 * The classifier head is GlobalAveragePooling -> Dense, so for class c the
 * class activation map CAM_c = ReLU(sum_k W[k, c] * A_k) equals Grad-CAM up to a
 * positive constant (the training pipeline verifies this numerically, see
 * ml/out/reports/explainability_check.json). A_k are the `features` output of
 * the LiteRT model and W is cam_weights.bin, exported from the trained head.
 *
 * This is a visualisation of what drove the model's score. It is NOT a lesion
 * segmentation and does not prove a prediction is correct.
 */
object ClassActivationMap {

    /** features: [fh * fw * k] (NHWC, N = 1); weights: [k * classes] row-major. Returns [fh * fw], normalised 0..1. */
    fun compute(features: FloatArray, fh: Int, fw: Int, k: Int, weights: FloatArray, classes: Int, cls: Int): FloatArray {
        require(features.size == fh * fw * k) { "feature size mismatch" }
        require(weights.size == k * classes && cls in 0 until classes) { "weight size mismatch" }
        val cam = FloatArray(fh * fw)
        var max = 0f
        for (p in 0 until fh * fw) {
            var s = 0.0
            val base = p * k
            for (ch in 0 until k) s += features[base + ch].toDouble() * weights[ch * classes + cls]
            val v = if (s > 0) s.toFloat() else 0f
            cam[p] = v
            if (v > max) max = v
        }
        if (max > 0f) for (i in cam.indices) cam[i] /= max
        return cam
    }

    /**
     * Renders the CAM onto the working-image frame (same aspect ratio as the photo
     * the user picked) as ARGB pixels: colour = jet colormap, alpha = activation.
     * Pixels outside the detected retina stay transparent, so the overlay lines up
     * with the original image in RetinalImageViewer.
     */
    fun renderOnWorkingFrame(cam: FloatArray, fh: Int, fw: Int, pre: Preprocessed): IntArray {
        val g = pre.geometry
        val w = g.workWidth
        val h = g.workHeight
        val out = IntArray(w * h)
        val side = g.side.toDouble()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!pre.mask[y * w + x]) continue
                // Position inside the square crop, then inside the CAM grid (half-pixel centres).
                val sy = (y - g.top + g.padTop + 0.5) / side * fh - 0.5
                val sx = (x - g.left + g.padLeft + 0.5) / side * fw - 0.5
                if (sy < -0.5 || sx < -0.5 || sy > fh - 0.5 || sx > fw - 0.5) continue
                val v = bilinear(cam, fh, fw, sy, sx)
                if (v <= 0.05f) continue
                out[y * w + x] = jet(v)
            }
        }
        return out
    }

    private fun bilinear(a: FloatArray, h: Int, w: Int, y: Double, x: Double): Float {
        val yc = y.coerceIn(0.0, (h - 1).toDouble())
        val xc = x.coerceIn(0.0, (w - 1).toDouble())
        val y0 = floor(yc).toInt()
        val x0 = floor(xc).toInt()
        val y1 = min(y0 + 1, h - 1)
        val x1 = min(x0 + 1, w - 1)
        val wy = yc - y0
        val wx = xc - x0
        val top = a[y0 * w + x0] * (1 - wx) + a[y0 * w + x1] * wx
        val bottom = a[y1 * w + x0] * (1 - wx) + a[y1 * w + x1] * wx
        return (top * (1 - wy) + bottom * wy).toFloat()
    }

    /** Jet colormap with alpha rising with activation. */
    fun jet(v: Float): Int {
        val t = v.coerceIn(0f, 1f)
        val r = (255 * (1.5f - kotlin.math.abs(4 * t - 3)).coerceIn(0f, 1f)).toInt()
        val g = (255 * (1.5f - kotlin.math.abs(4 * t - 2)).coerceIn(0f, 1f)).toInt()
        val b = (255 * (1.5f - kotlin.math.abs(4 * t - 1)).coerceIn(0f, 1f)).toInt()
        val a = (60 + 195 * t).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
