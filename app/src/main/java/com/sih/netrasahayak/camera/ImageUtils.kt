package com.sih.netrasahayak.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.sih.netrasahayak.AppConfig
import com.sih.netrasahayak.model.AppError
import com.sih.netrasahayak.model.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything image-related that sits between "the user picked a picture" and
 * "the bytes are ready to POST".
 *
 * Handles: large files (down-sampling), EXIF rotation, content:// URI reads,
 * temporary files and basic JPEG compression.
 *
 * The ORIGINAL image is copied untouched into the app's private storage so the
 * history screen can show it later. Only a separate, smaller *upload copy* is
 * compressed. The backend still performs all ML preprocessing.
 */
object ImageUtils {

    private const val TAG = "ImageUtils"

    private const val ORIGINALS_DIR = "screenings"
    private const val UPLOAD_DIR = "upload"
    private const val CAPTURE_DIR = "captures"

    // ---------------------------------------------------------------- files

    /** Directory CameraX writes freshly captured photos into. */
    fun captureDir(context: Context): File =
        File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }

    fun newCaptureFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(captureDir(context), "retina_$stamp.jpg")
    }

    // -------------------------------------------------------------- reading

    /**
     * Checks that the URI really points at a decodable image before the user is
     * allowed to continue.
     */
    suspend fun isReadableImage(context: Context, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                options.outWidth > 0 && options.outHeight > 0
            }.getOrDefault(false)
        }

    /**
     * Copies the picked/captured image into app-private storage so it survives
     * after the content:// permission grant expires. Nothing is re-encoded here.
     */
    suspend fun copyOriginalToAppStorage(context: Context, uri: Uri): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.filesDir, ORIGINALS_DIR).apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val target = File(dir, "original_$stamp.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                target
            }.onFailure { Log.w(TAG, "Could not copy original image", it) }
                .getOrNull()
        }

    // ------------------------------------------------------------ uploading

    /**
     * Produces the compressed, correctly-rotated JPEG that gets uploaded.
     * The result lives in the cache directory and is safe to delete at any time.
     */
    suspend fun prepareForUpload(context: Context, uri: Uri): Outcome<File> =
        withContext(Dispatchers.IO) {
            try {
                // NOTE: with inJustDecodeBounds the decode call always returns null
                // and only fills in `bounds` - so the stream must be null-checked
                // separately, and the size read from `bounds` afterwards.
                val boundsStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Outcome.Failure(AppError.InvalidImage)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return@withContext Outcome.Failure(AppError.InvalidImage)
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(
                        bounds.outWidth,
                        bounds.outHeight,
                        AppConfig.UPLOAD_MAX_DIMENSION
                    )
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }

                val decoded = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                } ?: return@withContext Outcome.Failure(AppError.InvalidImage)

                val rotation = readRotationDegrees(context, uri)
                val upright = applyRotation(decoded, rotation)
                val scaled = scaleToMaxDimension(upright, AppConfig.UPLOAD_MAX_DIMENSION)

                val dir = File(context.cacheDir, UPLOAD_DIR).apply { mkdirs() }
                val target = File(dir, "upload_${System.currentTimeMillis()}.jpg")
                FileOutputStream(target).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, AppConfig.UPLOAD_JPEG_QUALITY, out)
                }
                // Release every intermediate bitmap exactly once.
                if (!scaled.isRecycled) scaled.recycle()
                if (upright !== scaled && !upright.isRecycled) upright.recycle()
                if (decoded !== upright && !decoded.isRecycled) decoded.recycle()

                if (target.length() <= 0L) {
                    Outcome.Failure(AppError.InvalidImage)
                } else {
                    Outcome.Success(target)
                }
            } catch (oom: OutOfMemoryError) {
                Log.w(TAG, "Image too large to decode", oom)
                Outcome.Failure(AppError.ImageTooLarge)
            } catch (security: SecurityException) {
                Log.w(TAG, "No permission to read image URI", security)
                Outcome.Failure(AppError.InvalidImage)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to prepare image for upload", t)
                Outcome.Failure(AppError.InvalidImage)
            }
        }

    // --------------------------------------------------------------- helpers

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        var largest = maxOf(width, height)
        while (largest / 2 >= maxDimension) {
            largest /= 2
            sampleSize *= 2
        }
        return sampleSize
    }

    fun readRotationDegrees(context: Context, uri: Uri): Int =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)

    private fun applyRotation(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        // The caller owns cleanup of both bitmaps.
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleToMaxDimension(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / largest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
