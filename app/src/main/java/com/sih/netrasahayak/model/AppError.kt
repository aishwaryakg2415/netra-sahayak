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
        is Unknown -> details ?: stringResource(R.string.err_unknown)
    }
}
