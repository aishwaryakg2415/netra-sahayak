package com.sih.netrasahayak.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sih.netrasahayak.R
import com.sih.netrasahayak.camera.ImageUtils
import com.sih.netrasahayak.di.ServiceLocator
import com.sih.netrasahayak.model.AppError
import com.sih.netrasahayak.model.Outcome
import com.sih.netrasahayak.ui.components.ErrorView
import com.sih.netrasahayak.ui.components.LoadingView
import com.sih.netrasahayak.ui.components.PrimaryButton
import com.sih.netrasahayak.ui.components.ScreenPadding

/**
 * Live camera preview and a single big capture button.
 */
@Composable
fun CameraScreen(
    onCaptured: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val controller = remember { ServiceLocator.createCameraController() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    var startError by remember { mutableStateOf<AppError?>(null) }
    var captureError by remember { mutableStateOf<AppError?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) startError = AppError.CameraPermissionDenied
    }

    LaunchedEffect(hasPermission, permissionRequested) {
        if (!hasPermission && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(attempt, hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        startError = null
        when (val outcome = controller.start(lifecycleOwner, previewView)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> startError = outcome.error
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller.stop() }
    }

    if (startError != null) {
        ErrorView(
            message = startError!!.getLocalizedMessage(),
            onRetry = {
                if (hasPermission) {
                    attempt++
                } else {
                    startError = null
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            modifier = modifier.fillMaxSize()
        )
        return
    }

    if (!hasPermission) {
        LoadingView(
            title = stringResource(R.string.waiting_camera_permission),
            subtitle = stringResource(R.string.please_allow_camera),
            modifier = modifier.fillMaxSize()
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = ScreenPadding, vertical = 20.dp)
        ) {
            Text(
                text = stringResource(R.string.camera_guidance),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            if (captureError != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = captureError!!.getLocalizedMessage(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(16.dp))

            PrimaryButton(
                text = if (isCapturing) stringResource(R.string.capturing) else stringResource(R.string.capture),
                icon = Icons.Filled.PhotoCamera,
                enabled = !isCapturing,
                onClick = {
                    if (isCapturing) return@PrimaryButton
                    isCapturing = true
                    captureError = null
                    controller.capture(ImageUtils.newCaptureFile(context)) { outcome ->
                        isCapturing = false
                        when (outcome) {
                            is Outcome.Success -> onCaptured(outcome.data)
                            is Outcome.Failure -> captureError = outcome.error
                        }
                    }
                }
            )
        }
    }
}
