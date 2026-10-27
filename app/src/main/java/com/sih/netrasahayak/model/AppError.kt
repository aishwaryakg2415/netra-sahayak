package com.sih.netrasahayak.model

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sih.netrasahayak.R

/**
 * Every failure the user can see is one of these. Raw exceptions and stack
 * traces are never shown - only [message].
 */
sealed class AppError(val message: String) {

    data object NoImageSelected : AppError("No image selected.")

    data object CameraUnavailable : AppError("Unable to access camera.")

    data object CameraPermissionDenied :
        AppError("Camera permission is needed to take a photo. Please allow it in Settings.")

    data object InvalidImage : AppError("This file is not a valid image. Please choose another.")

    data object ImageTooLarge :
        AppError("This image is too large to process. Please use a smaller image.")

    data object NoInternet :
        AppError("No internet connection. Screening needs internet. Patient details and history still work offline.")

    data object Timeout : AppError("The screening server took too long to respond. Please try again.")

    data object ServerUnreachable : AppError("Unable to connect to the screening server. Please try again.")

    data class ServerError(val code: Int) :
        AppError("The screening server reported a problem (error $code). Please try again.")

    data object InvalidResponse :
        AppError("The screening server sent an unexpected reply. Please try again.")

    data object UploadFailed : AppError("Could not upload the image. Please try again.")

    data object StorageError : AppError("Could not save the screening on this phone.")

    data object OfflineModelUnavailable :
        AppError("Offline AI screening is not available in this version. Please connect to the internet.")

    /** The APK was built without the model assets. Never answered with a fake result. */
    data object ModelNotInstalled :
        AppError("The offline AI model is not installed in this app version, so screening cannot run.")

    /** Model or weights failed their SHA-256 check, or the config does not match this app. */
    data object ModelIntegrityFailed :
        AppError("The AI model failed its safety check and will not be used. Please reinstall the app.")

    data object InferenceFailed :
        AppError("The AI model could not analyse this image, so no result was produced. Please try again.")

    /** Image-quality gate said RETAKE_IMAGE. [reasons] are ImageQualityAssessor reason codes. */
    data class RetakeImage(val reasons: List<String>) :
        AppError("This photo is not good enough for AI screening. Please take a new retinal photo.")

    /** Image-quality gate said UNSUPPORTED_IMAGE (not plausibly a fundus photograph). */
    data object NotFundusImage :
        AppError("This does not look like a retinal (fundus) photograph.")

    data class Unknown(val details: String? = null) : AppError("Something went wrong. Please try again.")

    @Composable
    fun getLocalizedMessage(): String = when (this) {
        is NoImageSelected -> stringResource(R.string.err_no_image_selected)
        is CameraUnavailable -> stringResource(R.string.err_camera_unavailable)
        is CameraPermissionDenied -> stringResource(R.string.err_camera_permission_denied)
        is InvalidImage -> stringResource(R.string.err_invalid_image)
        is ImageTooLarge -> stringResource(R.string.err_image_too_large)
        is NoInternet -> stringResource(R.string.err_no_internet)
        is Timeout -> stringResource(R.string.err_timeout)
        is ServerUnreachable -> stringResource(R.string.err_server_unreachable)
        is ServerError -> stringResource(R.string.err_server_error, code)
        is InvalidResponse -> stringResource(R.string.err_invalid_response)
        is UploadFailed -> stringResource(R.string.err_upload_failed)
        is StorageError -> stringResource(R.string.err_storage_error)
        is OfflineModelUnavailable -> stringResource(R.string.err_offline_model_unavailable)
        is ModelNotInstalled -> stringResource(R.string.err_model_not_installed)
        is ModelIntegrityFailed -> stringResource(R.string.err_model_integrity)
        is InferenceFailed -> stringResource(R.string.err_inference_failed)
        is RetakeImage -> stringResource(
            R.string.err_retake_image,
            reasons.map { reasonText(it) }.distinct().joinToString(", ")
        )
        is NotFundusImage -> stringResource(R.string.err_not_fundus_image)
        is Unknown -> details ?: stringResource(R.string.err_unknown)
    }

    @Composable
    private fun reasonText(code: String): String = when (code) {
        "too_dark" -> stringResource(R.string.quality_reason_too_dark)
        "too_bright" -> stringResource(R.string.quality_reason_too_bright)
        "blurred" -> stringResource(R.string.quality_reason_blurred)
        "glare" -> stringResource(R.string.quality_reason_glare)
        "too_small" -> stringResource(R.string.quality_reason_too_small)
        else -> stringResource(R.string.quality_reason_other)
    }
}
