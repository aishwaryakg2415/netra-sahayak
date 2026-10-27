package com.sih.netrasahayak.ui.imagesource

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sih.netrasahayak.R
import com.sih.netrasahayak.camera.ImageUtils
import com.sih.netrasahayak.model.AppError
import com.sih.netrasahayak.ui.components.DisclaimerBanner
import com.sih.netrasahayak.ui.components.PrimaryButton
import com.sih.netrasahayak.ui.components.ScreenPadding
import kotlinx.coroutines.launch

/**
 * "Provide Retinal Image" - the fork between camera and gallery.
 */
@Composable
fun ImageSourceScreen(
    onOpenCamera: () -> Unit,
    onImageSelected: (android.net.Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var appError by remember { mutableStateOf<AppError?>(null) }
    var checkingImage by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            appError = AppError.NoImageSelected
        } else {
            checkingImage = true
            scope.launch {
                val readable = ImageUtils.isReadableImage(context, uri)
                checkingImage = false
                if (readable) {
                    appError = null
                    onImageSelected(uri)
                } else {
                    appError = AppError.InvalidImage
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            appError = null
            onOpenCamera()
        } else {
            appError = AppError.CameraPermissionDenied
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding, vertical = 24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = stringResource(R.string.provide_retinal_image),
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.provide_retinal_image_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(40.dp))

        PrimaryButton(
            text = stringResource(R.string.take_photo),
            icon = Icons.Filled.PhotoCamera,
            enabled = !checkingImage,
            onClick = {
                appError = null
                val alreadyGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                if (alreadyGranted) {
                    onOpenCamera()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        )

        Spacer(Modifier.height(20.dp))

        PrimaryButton(
            text = stringResource(R.string.choose_from_gallery),
            icon = Icons.Filled.PhotoLibrary,
            enabled = !checkingImage,
            onClick = {
                appError = null
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        )

        if (appError != null) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = appError!!.getLocalizedMessage(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(36.dp))

        Text(
            text = stringResource(R.string.fundus_camera_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))

        DisclaimerBanner()

        Spacer(Modifier.height(24.dp))
    }
}
