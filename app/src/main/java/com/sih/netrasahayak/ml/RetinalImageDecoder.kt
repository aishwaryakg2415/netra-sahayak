package com.sih.netrasahayak.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.sih.netrasahayak.camera.ImageUtils

/**
 * Decodes a picked/captured image into an upright [RgbImage] for the
 * preprocessor.
 *
 * Very large photos are decoded with a power-of-two inSampleSize, but never
 * below the preprocessor's 1024 px working size, so the reference pipeline's
 * own box downsampling still does the final reduction.
 */
object RetinalImageDecoder {

    fun decode(context: Context, uri: Uri): RgbImage? {
        // With inJustDecodeBounds, decodeStream returns null BY DESIGN and only fills
        // `bounds`; so null-check the stream, never the decode result.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= RetinaPreprocessor.WORK_MAX_SIDE * 2) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val rotation = ImageUtils.readRotationDegrees(context, uri)
        val upright = if (rotation == 0) decoded else {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
                .also { if (it !== decoded) decoded.recycle() }
        }
        val pixels = IntArray(upright.width * upright.height)
        upright.getPixels(pixels, 0, upright.width, 0, 0, upright.width, upright.height)
        val image = RgbImage.fromArgb(upright.width, upright.height, pixels)
        upright.recycle()
        return image
    }
}
