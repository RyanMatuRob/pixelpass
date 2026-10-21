package com.pixelpass.ui.components

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pixelpass.utils.QrCodeAnalyzer
import java.util.concurrent.Executors

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    isTorchOn: Boolean = false,
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Initialize CameraProvider once
    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, mainExecutor)
    }

    // Unbind and rebind camera whenever cameraProvider or lensFacing changes
    LaunchedEffect(cameraProvider, lensFacing) {
        val provider = cameraProvider ?: return@LaunchedEffect

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(
                    cameraExecutor,
                    QrCodeAnalyzer { jwt ->
                        mainExecutor.execute { onQrScanned(jwt) }
                    }
                )
            }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            provider.unbindAll()
            val boundCamera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            camera = boundCamera

            // Apply initial torch state if camera supports flash
            if (boundCamera.cameraInfo.hasFlashUnit()) {
                boundCamera.cameraControl.enableTorch(isTorchOn)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Dynamically toggle torch light whenever isTorchOn or camera instance changes
    LaunchedEffect(isTorchOn, camera) {
        val currentCamera = camera
        if (currentCamera != null && currentCamera.cameraInfo.hasFlashUnit()) {
            currentCamera.cameraControl.enableTorch(isTorchOn)
        }
    }

    // Clean up resources on disposal
    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize()
    )
}
