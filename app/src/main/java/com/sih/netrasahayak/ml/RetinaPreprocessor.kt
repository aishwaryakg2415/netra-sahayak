package com.sih.netrasahayak.ml

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin port of ml/netra_ml/preprocess.py. Pure Kotlin (no Android types) so it
 * runs in JVM unit tests against the golden files the Python reference wrote.
 *
 * Images are passed around as [RgbImage]: tightly packed RGB bytes, row-major.
 * Keep every step in sync with the Python file - the model was trained on the
 * Python output, so any drift here silently degrades accuracy.
 */
class RgbImage(val width: Int, val height: Int, val data: ByteArray) {
    init {
        require(width > 0 && height > 0 && data.size == width * height * 3) { "bad image buffer" }
    }

    fun r(x: Int, y: Int) = data[(y * width + x) * 3].toInt() and 0xFF
    fun g(x: Int, y: Int) = data[(y * width + x) * 3 + 1].toInt() and 0xFF
    fun b(x: Int, y: Int) = data[(y * width + x) * 3 + 2].toInt() and 0xFF

    companion object {
        /** From Android/AWT style packed ARGB ints. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): RgbImage {
            val out = ByteArray(width * height * 3)
            for (i in 0 until width * height) {
                val p = argb[i]
                out[i * 3] = (p shr 16 and 0xFF).toByte()
                out[i * 3 + 1] = (p shr 8 and 0xFF).toByte()
                out[i * 3 + 2] = (p and 0xFF).toByte()
            }
            return RgbImage(width, height, out)
        }
    }
}

data class Geometry(
    val workHeight: Int,
    val workWidth: Int,
    val top: Int,
    val left: Int,
    val bboxHeight: Int,
    val bboxWidth: Int,
    val side: Int,
    val padTop: Int,
    val padLeft: Int
)

class Preprocessed(
    val image: RgbImage,
    val work: RgbImage,
    val mask: BooleanArray,
    val geometry: Geometry
) {
    /** Model input: float32 RGB in [0, 255], NHWC with N = 1. */
    fun toModelInput(): FloatArray {
        val d = image.data
        return FloatArray(d.size) { (d[it].toInt() and 0xFF).toFloat() }
    }
}

class NoRetinaFoundException(message: String) : IllegalArgumentException(message)

object RetinaPreprocessor {

    const val WORK_MAX_SIDE = 1024
    const val MASK_THRESHOLD = 20
    const val BBOX_MIN_FRACTION = 0.02
    const val OUT_SIZE = 384

    fun boxDownsample(img: RgbImage, factor: Int): RgbImage {
        if (factor <= 1) return RgbImage(img.width, img.height, img.data.copyOf())
        val outW = img.width / factor
        val outH = img.height / factor
        require(outW > 0 && outH > 0) { "image smaller than the downsample factor" }
        val n = factor * factor
        val out = ByteArray(outW * outH * 3)
        val src = img.data
        for (oy in 0 until outH) {
            for (ox in 0 until outW) {
                var r = 0L
                var g = 0L
                var b = 0L
                for (dy in 0 until factor) {
                    var idx = ((oy * factor + dy) * img.width + ox * factor) * 3
                    for (dx in 0 until factor) {
                        r += src[idx].toInt() and 0xFF
                        g += src[idx + 1].toInt() and 0xFF
                        b += src[idx + 2].toInt() and 0xFF
                        idx += 3
                    }
                }
                val o = (oy * outW + ox) * 3
                out[o] = ((r + n / 2) / n).toInt().toByte()
                out[o + 1] = ((g + n / 2) / n).toInt().toByte()
                out[o + 2] = ((b + n / 2) / n).toInt().toByte()
            }
        }
        return RgbImage(outW, outH, out)
    }

    fun workingCopy(rgb: RgbImage): RgbImage {
        val longest = max(rgb.width, rgb.height)
        val factor = max(1, (longest + WORK_MAX_SIDE - 1) / WORK_MAX_SIDE)
        return boxDownsample(rgb, factor)
    }

    fun retinaMask(work: RgbImage): BooleanArray {
        val d = work.data
        return BooleanArray(work.width * work.height) { i ->
            (d[i * 3].toInt() and 0xFF) + (d[i * 3 + 1].toInt() and 0xFF) + (d[i * 3 + 2].toInt() and 0xFF) >
                3 * MASK_THRESHOLD
        }
    }

    /** Returns (top, left, height, width). */
    fun retinaBbox(mask: BooleanArray, w: Int, h: Int): IntArray {
        val rowCounts = IntArray(h)
        val colCounts = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x]) {
            rowCounts[y]++
            colCounts[x]++
        }
        val rowMin = max(1, (BBOX_MIN_FRACTION * w).toInt())
        val colMin = max(1, (BBOX_MIN_FRACTION * h).toInt())
        val top = rowCounts.indexOfFirst { it >= rowMin }
        val bottom = rowCounts.indexOfLast { it >= rowMin }
        val left = colCounts.indexOfFirst { it >= colMin }
        val right = colCounts.indexOfLast { it >= colMin }
        if (top < 0 || left < 0) throw NoRetinaFoundException("no bright region found")
        val bh = bottom - top + 1
        val bw = right - left + 1
        if (bh.toLong() * bw < 0.05 * h * w || min(bh, bw) < 32) {
            throw NoRetinaFoundException("bright region too small")
        }
        return intArrayOf(top, left, bh, bw)
    }

    /** Half-pixel-centre bilinear, edge clamped, double precision, round half up. */
    fun bilinearResize(img: RgbImage, outH: Int, outW: Int): RgbImage {
        val inH = img.height
        val inW = img.width
        val sy = inH.toDouble() / outH
        val sx = inW.toDouble() / outW
        val x0s = IntArray(outW)
        val x1s = IntArray(outW)
        val wxs = DoubleArray(outW)
        for (x in 0 until outW) {
            val xs = ((x + 0.5) * sx - 0.5).coerceIn(0.0, (inW - 1).toDouble())
            val x0 = floor(xs).toInt()
            x0s[x] = x0
            x1s[x] = min(x0 + 1, inW - 1)
            wxs[x] = xs - x0
        }
        val out = ByteArray(outW * outH * 3)
        val src = img.data
        for (y in 0 until outH) {
            val ys = ((y + 0.5) * sy - 0.5).coerceIn(0.0, (inH - 1).toDouble())
            val y0 = floor(ys).toInt()
            val y1 = min(y0 + 1, inH - 1)
            val wy = ys - y0
            for (x in 0 until outW) {
                val wx = wxs[x]
                for (c in 0 until 3) {
                    val a = (src[(y0 * inW + x0s[x]) * 3 + c].toInt() and 0xFF).toDouble()
                    val b = (src[(y0 * inW + x1s[x]) * 3 + c].toInt() and 0xFF).toDouble()
                    val cc = (src[(y1 * inW + x0s[x]) * 3 + c].toInt() and 0xFF).toDouble()
                    val d = (src[(y1 * inW + x1s[x]) * 3 + c].toInt() and 0xFF).toDouble()
                    // Same association order as the NumPy reference.
                    val top = a * (1.0 - wx) + b * wx
                    val bottom = cc * (1.0 - wx) + d * wx
                    val v = top * (1.0 - wy) + bottom * wy
                    out[(y * outW + x) * 3 + c] = floor(v + 0.5).coerceIn(0.0, 255.0).toInt().toByte()
                }
            }
        }
        return RgbImage(outW, outH, out)
    }

    fun preprocess(rgb: RgbImage, outSize: Int = OUT_SIZE): Preprocessed {
        val work = workingCopy(rgb)
        val mask = retinaMask(work)
        val (top, left, bh, bw) = retinaBbox(mask, work.width, work.height).let {
            listOf(it[0], it[1], it[2], it[3])
        }
        val side = max(bh, bw)
        val padTop = (side - bh) / 2
        val padLeft = (side - bw) / 2
        val square = ByteArray(side * side * 3)
        for (y in 0 until bh) {
            System.arraycopy(
                work.data, ((top + y) * work.width + left) * 3,
                square, ((padTop + y) * side + padLeft) * 3,
                bw * 3
            )
        }
        var sq = RgbImage(side, side, square)
        val g = side / outSize
        if (g >= 2) sq = boxDownsample(sq, g)
        val image = bilinearResize(sq, outSize, outSize)
        return Preprocessed(
            image, work, mask,
            Geometry(work.height, work.width, top, left, bh, bw, side, padTop, padLeft)
        )
    }
}
